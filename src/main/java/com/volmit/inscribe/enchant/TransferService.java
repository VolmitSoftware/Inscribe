package com.volmit.inscribe.enchant;

import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.inscribe.config.RuntimeConfig;
import com.volmit.inscribe.localization.LanguageService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class TransferService implements Listener, AutoCloseable {
    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final Supplier<RuntimeConfig> settings;
    private final LanguageService language;
    private final TransferPayment payment;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public TransferService(JavaPlugin plugin, Supplier<RuntimeConfig> settings, LanguageService language) {
        this.plugin = plugin;
        this.settings = settings;
        this.language = language;
        payment = new TransferPayment(plugin);
    }

    public int activeSessions() {
        return sessions.size();
    }

    public int pendingActions() {
        return pending.size();
    }

    public void invalidate() {
        for (Session session : sessions.values()) {
            FoliaScheduler.runEntity(plugin, session.player, () -> {
                if (session.player.getOpenInventory().getTopInventory() == session.inventory) {
                    session.player.closeInventory();
                }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        List<BukkitInventoryShutdown.View> views = new ArrayList<>();
        for (Session session : sessions.values()) {
            views.add(new BukkitInventoryShutdown.View(session.player, session.inventory));
        }
        BukkitInventoryShutdown.drain(plugin, views);
        sessions.clear();
        pending.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            boolean denied = event.isCancelled();
            event.setCancelled(true);
            if (!denied && event.getWhoClicked().getUniqueId().equals(session.player.getUniqueId())
                    && event.getRawSlot() >= 0 && event.getRawSlot() < 54
                    && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)) {
                int slot = event.getRawSlot();
                schedule(session.player, () -> menuClick(session, slot));
            }
            return;
        }
        if (event.isCancelled() || closed || event.getClick() != ClickType.RIGHT
                || !(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != player.getInventory() || event.getSlot() < 0 || event.getSlot() >= 36) {
            return;
        }
        ItemStack source = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (source == null || source.getType().isAir() || source.getAmount() != 1 || cursor == null
                || cursor.getType() != Material.BOOK && cursor.getType() != Material.ENCHANTED_BOOK) {
            return;
        }
        RuntimeConfig config = settings.get();
        boolean extraction = cursor.getType() == Material.BOOK;
        Set<Material> materials = extraction ? config.extractionMaterials() : config.applicationMaterials();
        if (!materials.isEmpty() && !materials.contains(source.getType())) {
            return;
        }
        if (source.getType() == Material.BOOK || source.getType() == Material.ENCHANTED_BOOK
                || !permitted(player, config, extraction) || plan(source, cursor, config, extraction).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        ItemStack expectedSource = source.clone();
        ItemStack expectedCursor = cursor.clone();
        int slot = event.getSlot();
        schedule(player, () -> {
            if (!matches(player.getInventory().getItem(slot), expectedSource)
                    || !matches(player.getItemOnCursor(), expectedCursor) || settings.get() != config) {
                language.send(player, "transfer.changed");
                return;
            }
            open(player, slot, expectedSource, expectedCursor, config, extraction ? Mode.EXTRACT : Mode.APPLY);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session session) {
            sessions.remove(event.getPlayer().getUniqueId(), session);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void schedule(Player player, Runnable action) {
        UUID id = player.getUniqueId();
        if (!pending.add(id)) {
            return;
        }
        Runnable retired = () -> pending.remove(id);
        if (!FoliaScheduler.runEntity(plugin, player, () -> {
            try {
                if (!closed && player.isOnline()) {
                    action.run();
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not process enchantment transfer for " + id, exception);
                language.send(player, "transfer.failed");
            } finally {
                retired.run();
            }
        }, 1L, retired)) {
            retired.run();
        }
    }

    private boolean allowed(Player player, RuntimeConfig config, boolean extraction) {
        String failure = !config.enabled() ? "transfer.disabled"
                : player.getGameMode() == GameMode.CREATIVE && !config.allowCreative() ? "transfer.creative"
                : !player.hasPermission(extraction ? "inscribe.extract" : "inscribe.apply") ? "transfer.denied" : null;
        if (failure != null) {
            language.send(player, failure);
        }
        return failure == null;
    }

    private boolean permitted(Player player, RuntimeConfig config, boolean extraction) {
        return config.enabled() && (player.getGameMode() != GameMode.CREATIVE || config.allowCreative())
                && player.hasPermission(extraction ? "inscribe.extract" : "inscribe.apply");
    }

    private static Map<Enchantment, Integer> plan(ItemStack source, ItemStack book, RuntimeConfig config, boolean extraction) {
        return extraction ? EnchantmentPlan.extractable(source, config) : EnchantmentPlan.applicable(source, book, config);
    }

    private void open(Player player, int slot, ItemStack source, ItemStack cursor, RuntimeConfig config, Mode mode) {
        boolean extraction = mode == Mode.EXTRACT;
        if (!allowed(player, config, extraction)) {
            return;
        }
        Map<Enchantment, Integer> available = plan(source, cursor, config, extraction);
        if (available.isEmpty()) {
            language.send(player, extraction ? "transfer.empty" : "transfer.incompatible");
            return;
        }
        int bookSlot = player.getInventory().firstEmpty();
        if (bookSlot < 0 || bookSlot >= 36) {
            language.send(player, "transfer.space");
            return;
        }
        Session session = new Session(player, new Input(mode, slot, source, bookSlot, cursor, config, available));
        if (!extraction) {
            session.selected.addAll(available.keySet());
        }
        session.inventory = Bukkit.createInventory(session, 54, language.text(player, "menu.title"));
        render(session);
        player.getInventory().setItem(bookSlot, cursor.clone());
        player.setItemOnCursor(null);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(session.inventory);
        if (player.getOpenInventory().getTopInventory() != session.inventory) {
            sessions.remove(player.getUniqueId(), session);
        }
    }

    private void menuClick(Session session, int slot) {
        Player player = session.player;
        if (sessions.get(player.getUniqueId()) != session || player.getOpenInventory().getTopInventory() != session.inventory) {
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (!valid(session) || !allowed(player, session.input.config(), session.input.mode() == Mode.EXTRACT)) {
            language.send(player, "transfer.changed");
            player.closeInventory();
            return;
        }
        int index = session.page * PAGE_SIZE + slot;
        List<Enchantment> available = new ArrayList<>(session.input.available().keySet());
        if (slot < PAGE_SIZE && index < available.size()) {
            Enchantment enchantment = available.get(index);
            if (!session.selected.remove(enchantment)) {
                session.selected.add(enchantment);
            }
        } else if (slot == 45 && session.page > 0) {
            session.page--;
        } else if (slot == 53 && (session.page + 1) * PAGE_SIZE < available.size()) {
            session.page++;
        } else if (slot == 51) {
            if (session.input.mode() == Mode.EXTRACT) {
                extract(session);
            } else {
                apply(session);
            }
            return;
        }
        render(session);
    }

    private void extract(Session session) {
        Player player = session.player;
        Input input = session.input;
        Map<Enchantment, Integer> selected = selected(session);
        if (selected.isEmpty()) {
            language.send(player, "transfer.select");
            return;
        }
        int outputSlot = input.book().getAmount() == 1 ? input.bookSlot() : player.getInventory().firstEmpty();
        if (outputSlot < 0 || outputSlot >= 36) {
            language.send(player, "transfer.space");
            return;
        }
        ItemStack outputBefore = copy(player.getInventory().getItem(outputSlot));
        ItemStack sourceAfter = input.source().clone();
        for (Enchantment enchantment : selected.keySet()) {
            sourceAfter.removeEnchantment(enchantment);
        }
        ItemStack book = createBook(player, selected, input.config());
        String failure = payment.execute(player, input.config(), EnchantmentPlan.extractionCost(selected, input.config()),
                () -> valid(session) && matches(player.getInventory().getItem(outputSlot), outputBefore), () -> {
            player.getInventory().setItem(input.slot(), sourceAfter);
            player.getInventory().setItem(input.bookSlot(), decrement(input.book()));
            player.getInventory().setItem(outputSlot, book);
        }, () -> {
            player.getInventory().setItem(input.slot(), input.source().clone());
            player.getInventory().setItem(outputSlot, outputBefore);
            player.getInventory().setItem(input.bookSlot(), input.book().clone());
        });
        finish(session, failure, "transfer.success");
    }

    private void finish(Session session, String failure, String success) {
        Player player = session.player;
        language.send(player, failure == null ? success : failure);
        if (player.getOpenInventory().getTopInventory() == session.inventory
                && (failure == null || failure.equals("transfer.changed") || failure.equals("transfer.failed")
                || failure.equals("transfer.reconciliation"))) {
            player.closeInventory();
        }
    }

    private void apply(Session session) {
        Player player = session.player;
        Input input = session.input;
        Map<Enchantment, Integer> selected = selected(session);
        if (selected.isEmpty()) {
            language.send(player, "transfer.select");
            return;
        }
        ItemStack sourceAfter = input.source().clone();
        sourceAfter.addUnsafeEnchantments(selected);
        if (input.config().increaseRepairCost()) {
            raiseRepairCost(sourceAfter, input.book());
        }
        ItemStack remainder = input.book().clone();
        remainder.setAmount(1);
        EnchantmentStorageMeta metadata = (EnchantmentStorageMeta) remainder.getItemMeta();
        for (Enchantment enchantment : selected.keySet()) {
            metadata.removeStoredEnchant(enchantment);
        }
        remainder.setItemMeta(metadata);
        boolean leftovers = metadata.hasStoredEnchants();
        boolean single = input.book().getAmount() == 1;
        int extraSlot = leftovers && !single ? player.getInventory().firstEmpty() : -1;
        if (leftovers && !single && (extraSlot < 0 || extraSlot >= 36)) {
            language.send(player, "transfer.space");
            return;
        }
        String failure = payment.execute(player, input.config(), EnchantmentPlan.applicationCost(selected, input.config()),
                () -> valid(session) && (extraSlot < 0 || empty(player.getInventory().getItem(extraSlot))), () -> {
            player.getInventory().setItem(input.slot(), sourceAfter);
            player.getInventory().setItem(input.bookSlot(), single ? leftovers ? remainder : null : decrement(input.book()));
            if (extraSlot >= 0) {
                player.getInventory().setItem(extraSlot, remainder);
            }
        }, () -> {
            player.getInventory().setItem(input.slot(), input.source().clone());
            player.getInventory().setItem(input.bookSlot(), input.book().clone());
            if (extraSlot >= 0) {
                player.getInventory().setItem(extraSlot, null);
            }
        });
        finish(session, failure, "transfer.applied");
    }

    private static void raiseRepairCost(ItemStack item, ItemStack book) {
        ItemMeta metadata = item.getItemMeta();
        if (metadata instanceof Repairable repairable) {
            int bookCost = book.getItemMeta() instanceof Repairable stored ? stored.getRepairCost() : 0;
            repairable.setRepairCost(EnchantmentPlan.increasedRepairCost(repairable.getRepairCost(), bookCost));
            item.setItemMeta(metadata);
        }
    }

    private boolean valid(Session session) {
        Input input = session.input;
        return !closed && sessions.get(session.player.getUniqueId()) == session
                && session.player.getOpenInventory().getTopInventory() == session.inventory
                && permitted(session.player, input.config(), input.mode() == Mode.EXTRACT) && settings.get() == input.config()
                && matches(session.player.getInventory().getItem(input.slot()), input.source())
                && matches(session.player.getInventory().getItem(input.bookSlot()), input.book());
    }

    private void render(Session session) {
        session.inventory.clear();
        boolean extraction = session.input.mode() == Mode.EXTRACT;
        RuntimeConfig config = session.input.config();
        List<Map.Entry<Enchantment, Integer>> entries = new ArrayList<>(session.input.available().entrySet());
        for (int slot = 0; slot < PAGE_SIZE && session.page * PAGE_SIZE + slot < entries.size(); slot++) {
            Map.Entry<Enchantment, Integer> entry = entries.get(session.page * PAGE_SIZE + slot);
            String name = entry.getKey().getKey().getKey().replace('_', ' ');
            boolean chosen = session.selected.contains(entry.getKey());
            String title = language.text(session.player, extraction ? chosen ? "menu.transfer" : "menu.keep"
                    : chosen ? "menu.apply" : "menu.skip", "name", name, "level", Integer.toString(entry.getValue()));
            int current = session.input.source().getEnchantmentLevel(entry.getKey());
            String detail = extraction
                    ? language.text(session.player, "menu.cost", "cost", cost(session.player,
                            EnchantmentPlan.extractionCost(Map.of(entry.getKey(), entry.getValue()), config)))
                    : current > 0 ? language.text(session.player, "menu.current", "level", Integer.toString(current)) : null;
            session.inventory.setItem(slot, detail == null ? icon(Material.ENCHANTED_BOOK, title)
                    : icon(Material.ENCHANTED_BOOK, title, detail));
        }
        if (session.page > 0) {
            session.inventory.setItem(45, icon(Material.ARROW, language.text(session.player, "menu.previous")));
        }
        session.inventory.setItem(49, icon(Material.BARRIER, language.text(session.player, "menu.cancel")));
        EnchantmentPlan.Cost total = extraction ? EnchantmentPlan.extractionCost(selected(session), config)
                : EnchantmentPlan.applicationCost(selected(session), config);
        session.inventory.setItem(51, icon(Material.LIME_CONCRETE,
                language.text(session.player, extraction ? "menu.confirm" : "menu.confirm_apply"),
                language.text(session.player, "menu.total", "cost", cost(session.player, total))));
        if ((session.page + 1) * PAGE_SIZE < entries.size()) {
            session.inventory.setItem(53, icon(Material.ARROW, language.text(session.player, "menu.next")));
        }
    }

    private Map<Enchantment, Integer> selected(Session session) {
        Map<Enchantment, Integer> selected = new LinkedHashMap<>();
        for (Enchantment enchantment : session.selected) {
            selected.put(enchantment, session.input.available().get(enchantment));
        }
        return selected;
    }

    private ItemStack createBook(Player player, Map<Enchantment, Integer> selected, RuntimeConfig config) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta metadata = (EnchantmentStorageMeta) book.getItemMeta();
        for (Map.Entry<Enchantment, Integer> entry : selected.entrySet()) {
            metadata.addStoredEnchant(entry.getKey(), entry.getValue(), true);
        }
        if (!config.bookName().isBlank()) {
            metadata.setDisplayName(color(config.bookName().replace("%player%", player.getName())));
        }
        if (!config.bookLore().isEmpty()) {
            metadata.setLore(config.bookLore().stream().map(line -> color(line.replace("%player%", player.getName()))).toList());
        }
        book.setItemMeta(metadata);
        return book;
    }

    private static ItemStack icon(Material material, String title, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta metadata = item.getItemMeta();
        metadata.setDisplayName(title);
        metadata.setLore(List.of(lore));
        item.setItemMeta(metadata);
        return item;
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private String cost(Player player, EnchantmentPlan.Cost cost) {
        String experience = Long.toString(cost.experience());
        String money = String.format(Locale.ROOT, "%.2f", cost.money());
        if (cost.experience() > 0 && cost.money() > 0) {
            return language.text(player, "menu.price", "experience", experience, "money", money);
        }
        if (cost.experience() > 0) {
            return language.text(player, "menu.experience", "experience", experience);
        }
        return cost.money() > 0 ? language.text(player, "menu.money", "money", money) : language.text(player, "menu.free");
    }

    private static ItemStack decrement(ItemStack item) {
        if (item.getAmount() == 1) {
            return null;
        }
        ItemStack result = item.clone();
        result.setAmount(item.getAmount() - 1);
        return result;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static boolean matches(ItemStack actual, ItemStack expected) {
        return empty(actual) ? empty(expected) : actual.equals(expected);
    }

    private static ItemStack copy(ItemStack item) {
        return empty(item) ? null : item.clone();
    }

    private enum Mode {
        EXTRACT,
        APPLY
    }

    private record Input(Mode mode, int slot, ItemStack source, int bookSlot, ItemStack book, RuntimeConfig config,
                         Map<Enchantment, Integer> available) {
    }

    private static final class Session implements InventoryHolder {
        private final Player player;
        private final Input input;
        private final Set<Enchantment> selected = new LinkedHashSet<>();
        private Inventory inventory;
        private int page;

        private Session(Player player, Input input) {
            this.player = player;
            this.input = input;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
