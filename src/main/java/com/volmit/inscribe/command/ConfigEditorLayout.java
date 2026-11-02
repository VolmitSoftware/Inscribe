package com.volmit.inscribe.command;

import art.arcane.volmlib.util.config.BukkitConfigEditor;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.TextKey;
import org.bukkit.Material;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

public final class ConfigEditorLayout {
    private static final TextKey TITLE = TextKey.of("setting.editor.title", "Inscribe / Settings");
    private static final BukkitConfigEditor.NumericControl MILLIS = new BukkitConfigEditor.NumericControl(250, 250, 60_000);
    private static final Map<String, Setting> SETTINGS = settings();

    private ConfigEditorLayout() {
    }

    public static void addMessages(MessageCatalog.Builder catalog) {
        catalog.add(TITLE);
        for (Setting setting : SETTINGS.values()) {
            catalog.add(setting.name());
            catalog.add(setting.description());
        }
    }

    public static BukkitConfigEditor.EditorLayout create(Consumer<Player> languages) {
        Setting locale = SETTINGS.get("general.language");
        return new BukkitConfigEditor.EditorLayout(TITLE, path -> presentation(path, languages),
                List.of(new BukkitConfigEditor.RootShortcut(
                        TextKey.of("command.description.language", "Select or edit languages"),
                        locale.description(), locale.icon(), languages)));
    }

    public static Set<List<String>> dynamicSections() {
        return Set.of(List.of("enchantments"));
    }

    private static BukkitConfigEditor.EntryPresentation presentation(List<String> path, Consumer<Player> languages) {
        String field = path.size() == 3 && path.getFirst().equals("enchantments") ? "policy." + path.getLast() : String.join(".", path);
        Setting setting = SETTINGS.get(field);
        return setting == null ? null : new BukkitConfigEditor.EntryPresentation(setting.name(), setting.description(),
                setting.icon(), setting.order(), setting.numeric(), null, field.equals("general.language") ? languages : null);
    }

    private static Map<String, Setting> settings() {
        Map<String, Setting> settings = new LinkedHashMap<>();
        add(settings, "general", "General", "Master switch, server language, and release notices.", Material.COMMAND_BLOCK, 0);
        add(settings, "metrics", "Metrics", "Anonymous bStats usage statistics.", Material.CHEST, 1);
        add(settings, "presentation", "Presentation", "Startup output.", Material.PAINTING, 2);
        add(settings, "hotReload", "Hot reload", "Apply edited files without a restart.", Material.COMPARATOR, 3);
        add(settings, "debug", "Diagnostics", "Diagnostic reports.", Material.SPYGLASS, 4);
        add(settings, "transfers", "Transfers", "Who can transfer, eligible items, and how books combine.", Material.ANVIL, 5);
        add(settings, "pricing", "Pricing", "Default prices and the payment currency.", Material.GOLD_INGOT, 6);
        add(settings, "enchantments", "Enchantment policies", "Per-enchantment extraction cost, currency and enabled state.", Material.ENCHANTED_BOOK, 7);
        add(settings, "general.enabled", "Enabled", "Enable extraction and application.", Material.ENCHANTED_BOOK, 0);
        add(settings, "general.language", "Server language", "Default language; personal choices take priority.", Material.WRITABLE_BOOK, 1);
        add(settings, "general.updateNotifications", "Release notifications", "Check for releases and notify permitted players. No automatic installation.", Material.BELL, 2);
        add(settings, "metrics.enabled", "Usage metrics", "Enable anonymous bStats usage reporting.", Material.COMPARATOR, 0);
        add(settings, "presentation.splashScreen", "Startup banner", "Show the plugin banner in the server log.", Material.PAPER, 0);
        add(settings, "hotReload.enabled", "Automatic reload", "Apply edited configuration and language files automatically.", Material.CLOCK, 0);
        settings.put("hotReload.pollIntervalMillis", new Setting(TextKey.of("setting.hotReload.pollIntervalMillis.name", "Check interval"),
                TextKey.of("setting.hotReload.pollIntervalMillis.description", "Milliseconds between file checks, 250 to 60000."), Material.REPEATER, 1, MILLIS));
        settings.put("hotReload.cooldownMillis", new Setting(TextKey.of("setting.hotReload.cooldownMillis.name", "Quiet period"),
                TextKey.of("setting.hotReload.cooldownMillis.description", "Milliseconds a file must stay unchanged before it applies."), Material.REPEATER, 2, MILLIS));
        add(settings, "hotReload.notifyOperators", "Operator notices", "Show applied or rejected file changes in the action bar.", Material.BELL, 3);
        add(settings, "debug.uploadEnabled", "Diagnostic uploads", "Allow uploads only when explicitly requested.", Material.PAPER, 0);
        add(settings, "transfers.allowCreative", "Creative mode", "Allow transfers in creative mode.", Material.GRASS_BLOCK, 0);
        add(settings, "transfers.allowUnsafe", "Above-maximum levels", "Keep higher levels; compatibility and conflicts still apply.", Material.ANVIL, 1);
        add(settings, "transfers.mergeEqualLevels", "Merge equal levels", "Combine equal book and item levels into the next level, like an anvil.", Material.EXPERIENCE_BOTTLE, 2);
        add(settings, "transfers.increaseRepairCost", "Raise repair cost", "Increase the item's repair cost when a book is applied, like an anvil.", Material.CHIPPED_ANVIL, 3);
        add(settings, "transfers.extractionMaterials", "Extraction materials", "Allowed item materials; an empty list allows eligible items.", Material.DIAMOND_PICKAXE, 4);
        add(settings, "transfers.applicationMaterials", "Application materials", "Allowed item materials; an empty list allows compatible items.", Material.DIAMOND_SWORD, 5);
        add(settings, "transfers.bookName", "Extracted book name", "Empty uses the vanilla name. Color formatting is supported.", Material.NAME_TAG, 6);
        add(settings, "transfers.bookLore", "Extracted book lore", "Up to 20 lines, 512 characters each. Empty adds no lore.", Material.BOOK, 7);
        add(settings, "pricing.currency", "Currency", "EXPERIENCE uses raw points. MONEY requires Vault.", Material.GOLD_INGOT, 0);
        add(settings, "pricing.extractionCost", "Extraction fee", "Default cost per selected enchantment, in points or money.", Material.EXPERIENCE_BOTTLE, 1);
        add(settings, "pricing.extractionCostPerLevel", "Extraction by level", "Multiply each extraction fee by the enchantment level.", Material.EXPERIENCE_BOTTLE, 2);
        add(settings, "pricing.applicationCost", "Application fee", "Cost per application; zero is free.", Material.ANVIL, 3);
        add(settings, "pricing.applicationCostPerLevel", "Application by level", "Multiply the application fee by the sum of applied levels.", Material.ANVIL, 4);
        add(settings, "pricing.allowEconomyOnFolia", "Folia money payments", "Enable only when your economy provider supports Folia.", Material.GOLD_INGOT, 5);
        add(settings, "policy.enabled", "Enabled", "Allow this enchantment to transfer.", Material.LEVER, 0);
        add(settings, "policy.cost", "Cost", "Extraction price for this enchantment.", Material.GOLD_NUGGET, 1);
        add(settings, "policy.perLevel", "Per level", "Multiply the price by the enchantment level.", Material.EXPERIENCE_BOTTLE, 2);
        add(settings, "policy.currency", "Currency", "EXPERIENCE uses points; MONEY uses Vault.", Material.GOLD_INGOT, 3);
        return settings;
    }

    private static void add(Map<String, Setting> settings, String path, String name, String description, Material icon, int order) {
        settings.put(path, new Setting(TextKey.of("setting." + path + ".name", name),
                TextKey.of("setting." + path + ".description", description), icon, order, null));
    }

    private record Setting(TextKey name, TextKey description, Material icon, int order,
                           BukkitConfigEditor.NumericControl numeric) {
    }
}
