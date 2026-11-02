package com.volmit.inscribe.enchant;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.volmlib.integration.VaultEconomy;
import com.volmit.inscribe.config.Currency;
import com.volmit.inscribe.config.InscribeConfig;
import com.volmit.inscribe.config.RuntimeConfig;
import com.volmit.inscribe.localization.LanguageService;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class TransferServiceTest {
    private PlayerMock player;
    private TransferService service;
    private AtomicReference<RuntimeConfig> settings;
    private MockedStatic<FoliaScheduler> scheduler;
    private JavaPlugin plugin;
    private LanguageService language;
    private final List<Runnable> tasks = new ArrayList<>();

    @BeforeEach
    void start() {
        ServerMock server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.setOp(true);
        language = mock(LanguageService.class);
        when(language.text(any(), anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(1));
        InscribeConfig config = new InscribeConfig();
        config.pricing.extractionCost = 10;
        settings = new AtomicReference<>(RuntimeConfig.from(config));
        service = new TransferService(plugin, settings::get, language);
        server.getPluginManager().registerEvents(service, plugin);
        scheduler = mockStatic(FoliaScheduler.class);
        scheduler.when(() -> FoliaScheduler.runEntity(any(Plugin.class), any(Entity.class), any(Runnable.class), anyLong(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    tasks.add(invocation.getArgument(2));
                    return true;
                });
    }

    @AfterEach
    void stop() {
        scheduler.close();
        MockBukkit.unmock();
    }

    @Test
    void providerClosingConfirmationRefundsWithoutTransferring() {
        InscribeConfig config = new InscribeConfig();
        config.pricing.currency = Currency.MONEY;
        config.pricing.extractionCost = 10;
        settings.set(RuntimeConfig.from(config));
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        when(charge.refund()).thenReturn(true);
        Inventory replacement = MockBukkit.getMock().createInventory(null, 9);
        try (MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class, (economy, context) -> {
            when(economy.isAvailable()).thenReturn(true);
            when(economy.withdraw(any(), anyDouble(), anyString())).thenAnswer(invocation -> {
                player.closeInventory();
                player.openInventory(replacement);
                return VaultEconomy.ChargeResult.success(charge);
            });
        })) {
            service = new TransferService(plugin, settings::get, language);
            MockBukkit.getMock().getPluginManager().registerEvents(service, plugin);
            ItemStack source = sword();
            player.getInventory().setItem(0, source);
            player.setItemOnCursor(new ItemStack(Material.BOOK));
            player.giveExp(200);
            clickSource();
            clickMenu(0);
            clickMenu(51);
            assertThat(player.getInventory().getItem(0)).isEqualTo(source);
            assertThat(player.getInventory().getItem(1).getType()).isEqualTo(Material.BOOK);
            assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(200);
            verify(charge).refund();
            verify(charge, never()).commit();
            assertThat(player.getOpenInventory().getTopInventory()).isSameAs(replacement);
        }
    }

    @Test
    void oversizedExperienceQuoteStaysExactAndRejectsWithoutMutation() {
        InscribeConfig config = new InscribeConfig();
        config.pricing.extractionCost = 1_000_000;
        config.pricing.extractionCostPerLevel = true;
        settings.set(RuntimeConfig.from(config));
        ItemStack source = new ItemStack(Material.DIAMOND_SWORD);
        source.addUnsafeEnchantment(Enchantment.SHARPNESS, 32767);
        player.getInventory().setItem(0, source);
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        player.giveExp(200);
        clickSource();
        clickMenu(0);
        verify(language, atLeastOnce()).text(player, "menu.experience", "experience", "32767000000");
        clickMenu(51);
        verify(language).send(player, "transfer.unaffordable");
        assertThat(player.getInventory().getItem(0)).isEqualTo(source);
        assertThat(player.getInventory().getItem(1).getType()).isEqualTo(Material.BOOK);
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(200);
    }

    @Test
    void extractionConsumesOneBookAndExactXpOnlyOnConfirmation() {
        ItemStack sword = sword();
        player.getInventory().setItem(0, sword);
        player.setItemOnCursor(new ItemStack(Material.BOOK, 3));
        player.giveExp(200);
        clickSource();
        assertThat(player.getInventory().getItem(0)).isEqualTo(sword);
        assertThat(player.getInventory().getItem(1).getAmount()).isEqualTo(3);
        clickMenu(0);
        clickMenu(51);
        assertThat(player.getInventory().getItem(0).containsEnchantment(Enchantment.SHARPNESS)).isFalse();
        assertThat(player.getInventory().getItem(0).containsEnchantment(Enchantment.UNBREAKING)).isTrue();
        assertThat(player.getInventory().getItem(1).getAmount()).isEqualTo(2);
        EnchantmentStorageMeta book = (EnchantmentStorageMeta) player.getInventory().getItem(2).getItemMeta();
        assertThat(book.getStoredEnchants()).containsExactly(Map.entry(Enchantment.SHARPNESS, 3));
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(190);
    }

    @Test
    void changedSourceAbortsWithoutConsumingBookOrXp() {
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        player.giveExp(200);
        clickSource();
        clickMenu(0);
        ItemStack replacement = new ItemStack(Material.DIAMOND_SWORD);
        player.getInventory().setItem(0, replacement);
        clickMenu(51);
        assertThat(player.getInventory().getItem(0)).isEqualTo(replacement);
        assertThat(player.getInventory().getItem(1).getType()).isEqualTo(Material.BOOK);
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(200);
    }

    @Test
    void changedSettingsAbortExistingQuote() {
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        clickSource();
        clickMenu(0);
        settings.set(RuntimeConfig.from(new InscribeConfig()));
        clickMenu(51);
        assertThat(player.getInventory().getItem(0).getEnchantments()).hasSize(2);
        assertThat(player.getInventory().getItem(1).getType()).isEqualTo(Material.BOOK);
    }

    @Test
    void partialApplicationPreservesIncompatibleStoredEnchantments() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3, Enchantment.FORTUNE, 2)));
        clickSource();
        assertThat(player.getInventory().getItem(0).getEnchantments()).isEmpty();
        clickMenu(51);
        assertThat(player.getInventory().getItem(0).getEnchantments()).containsExactly(Map.entry(Enchantment.SHARPNESS, 3));
        EnchantmentStorageMeta remaining = (EnchantmentStorageMeta) player.getInventory().getItem(1).getItemMeta();
        assertThat(remaining.getStoredEnchants()).containsExactly(Map.entry(Enchantment.FORTUNE, 2));
        assertThat(player.getItemOnCursor().getType()).isEqualTo(Material.AIR);
        verify(language).send(player, "transfer.applied");
    }

    @Test
    void applicationWaitsForConfirmationAndHonorsDeselection() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        ItemStack book = EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3));
        player.setItemOnCursor(book);
        clickSource();
        Inventory menu = player.getOpenInventory().getTopInventory();
        clickMenu(0);
        clickMenu(51);
        verify(language).send(player, "transfer.select");
        assertThat(player.getInventory().getItem(0).getEnchantments()).isEmpty();
        assertThat(player.getInventory().getItem(1)).isEqualTo(book);
        clickMenu(49);
        assertThat(player.getOpenInventory().getTopInventory()).isNotSameAs(menu);
        assertThat(player.getInventory().getItem(1)).isEqualTo(book);
    }

    @Test
    void stackedMixedBooksPreserveUntouchedStackAndSeparateRemainder() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        ItemStack book = EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3, Enchantment.FORTUNE, 2));
        book.setAmount(2);
        player.setItemOnCursor(book);
        clickSource();
        clickMenu(51);
        assertThat(player.getInventory().getItem(1).getAmount()).isEqualTo(1);
        assertThat(((EnchantmentStorageMeta) player.getInventory().getItem(1).getItemMeta()).getStoredEnchants()).hasSize(2);
        assertThat(((EnchantmentStorageMeta) player.getInventory().getItem(2).getItemMeta()).getStoredEnchants())
                .containsExactly(Map.entry(Enchantment.FORTUNE, 2));
    }

    @Test
    void applicationMergesEqualLevelsAndRaisesRepairCost() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        player.getInventory().setItem(0, sword);
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3)));
        clickSource();
        clickMenu(51);
        ItemStack result = player.getInventory().getItem(0);
        assertThat(result.getEnchantments()).containsExactly(Map.entry(Enchantment.SHARPNESS, 4));
        assertThat(((Repairable) result.getItemMeta()).getRepairCost()).isEqualTo(1);
        assertThat(player.getInventory().getItem(1)).isNull();
    }

    @Test
    void disabledAnvilOptionsKeepLevelsAndRepairCost() {
        InscribeConfig config = new InscribeConfig();
        config.transfers.mergeEqualLevels = false;
        config.transfers.increaseRepairCost = false;
        settings.set(RuntimeConfig.from(config));
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        player.getInventory().setItem(0, sword);
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3)));
        InventoryClickEvent event = sourceEvent();
        service.onClick(event);
        verify(event, never()).setCancelled(true);
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 1)));
        clickSource();
        clickMenu(51);
        ItemStack result = player.getInventory().getItem(0);
        assertThat(result.getEnchantments()).containsOnly(Map.entry(Enchantment.SHARPNESS, 3), Map.entry(Enchantment.UNBREAKING, 1));
        assertThat(((Repairable) result.getItemMeta()).getRepairCost()).isZero();
    }

    @Test
    void applicationFeeChargesExactExperienceOnConfirmation() {
        InscribeConfig config = new InscribeConfig();
        config.pricing.applicationCost = 5;
        settings.set(RuntimeConfig.from(config));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3)));
        player.giveExp(200);
        clickSource();
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(200);
        clickMenu(51);
        assertThat(player.getInventory().getItem(0).getEnchantments()).containsExactly(Map.entry(Enchantment.SHARPNESS, 3));
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(195);
    }

    @Test
    void unaffordableApplicationKeepsTheBookAndItem() {
        InscribeConfig config = new InscribeConfig();
        config.pricing.applicationCost = 500;
        settings.set(RuntimeConfig.from(config));
        ItemStack book = EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 3));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.setItemOnCursor(book);
        player.giveExp(200);
        clickSource();
        clickMenu(51);
        verify(language).send(player, "transfer.unaffordable");
        assertThat(player.getInventory().getItem(0).getEnchantments()).isEmpty();
        assertThat(player.getInventory().getItem(1)).isEqualTo(book);
        assertThat(TransferPayment.points(player.getLevel(), player.getExp())).isEqualTo(200);
    }

    @Test
    void extractedBooksCarryNoLoreUnlessConfigured() {
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        player.giveExp(200);
        clickSource();
        clickMenu(0);
        clickMenu(51);
        assertThat(player.getInventory().getItem(1).getItemMeta().hasLore()).isFalse();
    }

    @Test
    void fullInventoryDoesNotLoseCursorBooks() {
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        ItemStack sword = sword();
        player.getInventory().setItem(0, sword);
        player.setItemOnCursor(new ItemStack(Material.BOOK, 3));
        clickSource();
        assertThat(player.getInventory().getItem(0)).isEqualTo(sword);
        assertThat(player.getItemOnCursor()).isEqualTo(new ItemStack(Material.BOOK, 3));
    }

    @Test
    void alreadyCancelledInteractionCannotTransfer() {
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        InventoryClickEvent event = sourceEvent();
        when(event.isCancelled()).thenReturn(true);
        service.onClick(event);
        assertThat(tasks).isEmpty();
        assertThat(player.getItemOnCursor().getType()).isEqualTo(Material.BOOK);
    }

    @Test
    void aboveMaximumBookLevelsLeaveTheClickToVanilla() {
        ItemStack target = new ItemStack(Material.DIAMOND_SWORD);
        ItemStack book = EnchantmentPlanTest.book(Map.of(Enchantment.SHARPNESS, 10));
        player.getInventory().setItem(0, target);
        player.setItemOnCursor(book);
        InventoryClickEvent event = sourceEvent();
        service.onClick(event);
        verify(event, never()).setCancelled(true);
        verify(language, never()).send(any(), anyString());
        assertThat(tasks).isEmpty();
        assertThat(player.getInventory().getItem(0)).isEqualTo(target);
        assertThat(player.getItemOnCursor()).isEqualTo(book);
    }

    @Test
    void booksOnItemsWithNothingToTransferLeaveTheClickToVanilla() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        InventoryClickEvent extraction = sourceEvent();
        service.onClick(extraction);
        verify(extraction, never()).setCancelled(true);
        player.setItemOnCursor(EnchantmentPlanTest.book(Map.of(Enchantment.FORTUNE, 2)));
        InventoryClickEvent application = sourceEvent();
        service.onClick(application);
        verify(application, never()).setCancelled(true);
        player.setOp(false);
        player.addAttachment(plugin, "inscribe.extract", false);
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        InventoryClickEvent denied = sourceEvent();
        service.onClick(denied);
        verify(denied, never()).setCancelled(true);
        verify(language, never()).send(any(), anyString());
        assertThat(tasks).isEmpty();
    }

    @Test
    void freeTransferQuotesShowFreeInsteadOfZeroPrices() {
        InscribeConfig config = new InscribeConfig();
        config.pricing.extractionCost = 0;
        settings.set(RuntimeConfig.from(config));
        player.getInventory().setItem(0, sword());
        player.setItemOnCursor(new ItemStack(Material.BOOK));
        clickSource();
        verify(language, atLeastOnce()).text(player, "menu.free");
        verify(language, never()).text(eq(player), eq("menu.price"), any(String[].class));
    }

    private ItemStack sword() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
        return sword;
    }

    private void clickSource() {
        service.onClick(sourceEvent());
        drain();
    }

    private InventoryClickEvent sourceEvent() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(player.getInventory());
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(player.getInventory());
        when(event.getSlot()).thenReturn(0);
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(event.getCurrentItem()).thenReturn(player.getInventory().getItem(0));
        when(event.getCursor()).thenReturn(player.getItemOnCursor());
        return event;
    }

    private void clickMenu(int slot) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        service.onClick(event);
        drain();
    }

    private void drain() {
        List<Runnable> scheduled = List.copyOf(tasks);
        tasks.clear();
        scheduled.forEach(Runnable::run);
    }
}
