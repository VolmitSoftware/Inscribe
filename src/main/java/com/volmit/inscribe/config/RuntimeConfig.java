package com.volmit.inscribe.config;

import art.arcane.volmlib.util.localization.LanguageDirectory;
import org.bukkit.Material;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuntimeConfig(boolean enabled, String defaultLocale, boolean updateNotifications, boolean metricsEnabled,
        boolean splashScreen, boolean hotReloadEnabled, long pollIntervalMillis, long cooldownMillis,
        boolean notifyOperators, boolean debugUpload, boolean allowCreative, boolean allowUnsafe,
        boolean mergeEqualLevels, boolean increaseRepairCost, Set<Material> extractionMaterials,
        Set<Material> applicationMaterials, String bookName, List<String> bookLore, Currency currency,
        int extractionCost, boolean extractionCostPerLevel, int applicationCost, boolean applicationCostPerLevel,
        boolean allowEconomyOnFolia, Map<String, EnchantmentPolicy> enchantments) {
    private static final int MAX_TEXT = 512;
    private static final int MAX_LORE_LINES = 20;
    private static final long MIN_HOT_RELOAD_MILLIS = 250L;
    private static final long MAX_HOT_RELOAD_MILLIS = 60_000L;

    public RuntimeConfig {
        extractionMaterials = Set.copyOf(extractionMaterials);
        applicationMaterials = Set.copyOf(applicationMaterials);
        bookLore = List.copyOf(bookLore);
        enchantments = Map.copyOf(enchantments);
    }

    public static RuntimeConfig from(InscribeConfig source) {
        InscribeConfig.General general = section("general", source.general);
        InscribeConfig.Metrics metrics = section("metrics", source.metrics);
        InscribeConfig.Presentation presentation = section("presentation", source.presentation);
        InscribeConfig.HotReload hotReload = section("hotReload", source.hotReload);
        InscribeConfig.Debug debug = section("debug", source.debug);
        InscribeConfig.Transfers transfers = section("transfers", source.transfers);
        InscribeConfig.Pricing pricing = section("pricing", source.pricing);
        if (general.language == null || !LanguageDirectory.LOCALE_PATTERN.matcher(general.language).matches()) {
            throw new IllegalArgumentException("general.language must be a locale ID of 2 to 32 letters, digits, underscores, or hyphens");
        }
        if (pricing.currency == null) {
            throw new IllegalArgumentException("pricing.currency must be EXPERIENCE or MONEY");
        }
        cost("pricing.extractionCost", pricing.extractionCost);
        cost("pricing.applicationCost", pricing.applicationCost);
        if (transfers.bookName == null || transfers.bookName.length() > MAX_TEXT) {
            throw new IllegalArgumentException("transfers.bookName must be text of at most " + MAX_TEXT + " characters");
        }
        if (transfers.bookLore == null || transfers.bookLore.size() > MAX_LORE_LINES) {
            throw new IllegalArgumentException("transfers.bookLore must be a list of at most " + MAX_LORE_LINES + " lines");
        }
        for (int index = 0; index < transfers.bookLore.size(); index++) {
            String line = transfers.bookLore.get(index);
            if (line == null || line.length() > MAX_TEXT) {
                throw new IllegalArgumentException("transfers.bookLore line " + (index + 1) + " must be text of at most " + MAX_TEXT + " characters");
            }
        }
        if (source.enchantments == null) {
            throw new IllegalArgumentException("enchantments must be a table of policies");
        }
        for (Map.Entry<String, EnchantmentPolicy> entry : source.enchantments.entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("enchantments key " + entry.getKey() + " must be a namespaced ID such as minecraft:sharpness");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("enchantments." + entry.getKey() + " must define enabled, cost, perLevel and currency");
            }
            entry.getValue().validated("enchantments." + entry.getKey());
        }
        return new RuntimeConfig(general.enabled, general.language, general.updateNotifications, metrics.enabled,
                presentation.splashScreen, hotReload.enabled, clamp(hotReload.pollIntervalMillis), clamp(hotReload.cooldownMillis),
                hotReload.notifyOperators, debug.uploadEnabled, transfers.allowCreative, transfers.allowUnsafe,
                transfers.mergeEqualLevels, transfers.increaseRepairCost,
                materials("transfers.extractionMaterials", transfers.extractionMaterials),
                materials("transfers.applicationMaterials", transfers.applicationMaterials),
                transfers.bookName, transfers.bookLore, pricing.currency, pricing.extractionCost, pricing.extractionCostPerLevel,
                pricing.applicationCost, pricing.applicationCostPerLevel, pricing.allowEconomyOnFolia, source.enchantments);
    }

    public EnchantmentPolicy policy(String key) {
        EnchantmentPolicy override = enchantments.get(key);
        return override == null ? new EnchantmentPolicy(true, extractionCost, extractionCostPerLevel, currency) : override;
    }

    private static <T> T section(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be a table");
        }
        return value;
    }

    private static void cost(String field, int value) {
        if (value < 0 || value > EnchantmentPolicy.MAX_COST) {
            throw new IllegalArgumentException(field + " must be between 0 and " + EnchantmentPolicy.MAX_COST);
        }
    }

    private static long clamp(long value) {
        return Math.max(MIN_HOT_RELOAD_MILLIS, Math.min(MAX_HOT_RELOAD_MILLIS, value));
    }

    private static Set<Material> materials(String field, List<String> names) {
        if (names == null) {
            throw new IllegalArgumentException(field + " must be a list of item materials");
        }
        Set<Material> result = new HashSet<>();
        for (String name : names) {
            Material material = name == null ? null : Material.matchMaterial(name);
            if (material == null || !material.isItem() || material.isAir()) {
                throw new IllegalArgumentException(field + " contains unknown item material " + name);
            }
            result.add(material);
        }
        return result;
    }
}
