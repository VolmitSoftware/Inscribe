package com.volmit.inscribe.config;

import art.arcane.volmlib.util.config.ConfigDescription;
import art.arcane.volmlib.util.config.ConfigDoc;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;

@ConfigDescription("Inscribe enchantment extraction, application, and pricing.")
public final class InscribeConfig {
    public General general = new General();
    public Metrics metrics = new Metrics();
    public Presentation presentation = new Presentation();
    public HotReload hotReload = new HotReload();
    public Debug debug = new Debug();
    public Transfers transfers = new Transfers();
    public Pricing pricing = new Pricing();
    @ConfigDoc(value = "Per-enchantment extraction policies keyed by namespaced IDs, each with enabled, cost, perLevel and currency.",
            impact = "Enchantments without a rule use the pricing defaults; disabled enchantments stay on their items.")
    public Map<String, EnchantmentPolicy> enchantments = new TreeMap<>(Map.of(
            "minecraft:binding_curse", new EnchantmentPolicy(false, 100, false, Currency.EXPERIENCE),
            "minecraft:vanishing_curse", new EnchantmentPolicy(false, 100, false, Currency.EXPERIENCE)));

    @ConfigDescription("Master switch, server language, and release notices.")
    public static final class General {
        @ConfigDoc(value = "Enable inventory enchantment extraction and application.",
                impact = "While disabled, books and items behave as in vanilla.")
        public boolean enabled = true;
        @ConfigDoc(value = "Server default language: en_US, a repository locale, or the name of any languages/<id>.toml file.",
                impact = "Personal selections override this setting.")
        public String language = "en_US";
        @ConfigDoc(value = "Notify players with inscribe.update about new GitHub releases.",
                impact = "Updates are never downloaded or installed.")
        public boolean updateNotifications = true;
    }

    @ConfigDescription("Anonymous bStats usage statistics.")
    public static final class Metrics {
        @ConfigDoc(value = "Submit anonymous Inscribe usage statistics to bStats.",
                impact = "The shared plugins/bStats/config.yml opt-out remains authoritative; changes apply without a restart.")
        public boolean enabled = true;
    }

    @ConfigDescription("Startup output.")
    public static final class Presentation {
        @ConfigDoc(value = "Print the Inscribe startup banner.",
                impact = "Read at startup; the ready line is always logged.")
        public boolean splashScreen = true;
    }

    @ConfigDescription("Apply edited configuration and language files without a restart.")
    public static final class HotReload {
        @ConfigDoc(value = "Apply edits to config.toml and languages/<locale>.toml automatically.",
                impact = "Edits made while disabled are not replayed when it is enabled again.")
        public boolean enabled = true;
        @ConfigDoc(value = "Milliseconds between file checks, from 250 to 60000.",
                impact = "Values outside the range are clamped.")
        public long pollIntervalMillis = 1_000L;
        @ConfigDoc(value = "Milliseconds a changed file must stay unchanged before it is applied, from 250 to 60000.",
                impact = "Combines rapid saves into one reload; values outside the range are clamped.")
        public long cooldownMillis = 1_500L;
        @ConfigDoc(value = "Show an action bar line to players with inscribe.config when file changes are applied or rejected.",
                impact = "The console logs the same outcome either way.")
        public boolean notifyOperators = true;
    }

    @ConfigDescription("Diagnostic reports.")
    public static final class Debug {
        @ConfigDoc(value = "Allow /inscribe debug dump upload=true to upload the saved report.",
                impact = "Reports are always saved locally; uploads happen only when explicitly requested.")
        public boolean uploadEnabled = true;
    }

    @ConfigDescription("Who can transfer, which items qualify, and how books combine.")
    public static final class Transfers {
        @ConfigDoc(value = "Allow transfers in creative mode.",
                impact = "Survival and adventure modes are always allowed.")
        public boolean allowCreative = false;
        @ConfigDoc(value = "Allow enchantment levels above vanilla maximums.",
                impact = "Material compatibility and conflicts still apply.")
        public boolean allowUnsafe = false;
        @ConfigDoc(value = "Combine a book level equal to the item's level into the next level, as an anvil does.",
                impact = "Never raises a level past the vanilla maximum.")
        public boolean mergeEqualLevels = true;
        @ConfigDoc(value = "Raise the item's repair cost when a book is applied, as an anvil does.",
                impact = "The new cost is twice the higher of the item's and book's costs, plus one.")
        public boolean increaseRepairCost = true;
        @ConfigDoc(value = "Item material allowlist for extraction.",
                impact = "An empty list allows every enchanted item.")
        public List<String> extractionMaterials = List.of();
        @ConfigDoc(value = "Item material allowlist for application.",
                impact = "An empty list allows every compatible item.")
        public List<String> applicationMaterials = List.of();
        @ConfigDoc(value = "Extracted book display name; %player% is replaced by the player's name.",
                impact = "Empty keeps the vanilla name. Supports legacy & color codes.")
        public String bookName = "";
        @ConfigDoc(value = "Extracted book lore, up to 20 lines of 512 characters each.",
                impact = "Empty adds no lore, so extracted books stack with matching vanilla books.")
        public List<String> bookLore = List.of();
    }

    @ConfigDescription("Default prices and the payment currency.")
    public static final class Pricing {
        @ConfigDoc(value = "EXPERIENCE charges raw experience points, not levels. MONEY uses Vault.",
                impact = "MONEY requires Vault and a registered economy provider.")
        public Currency currency = Currency.EXPERIENCE;
        @ConfigDoc(value = "Default extraction fee per selected enchantment, from 0 to 1000000.",
                impact = "Per-enchantment rules override this fee.")
        public int extractionCost = 100;
        @ConfigDoc(value = "Multiply each extraction fee by that enchantment's level.",
                impact = "Off charges the same fee at every level.")
        public boolean extractionCostPerLevel = false;
        @ConfigDoc(value = "Fee per application, from 0 to 1000000.",
                impact = "Zero makes application free.")
        public int applicationCost = 0;
        @ConfigDoc(value = "Multiply the application fee by the sum of the applied levels.",
                impact = "Off charges one fee per application.")
        public boolean applicationCostPerLevel = false;
        @ConfigDoc(value = "Allow Vault payments on Folia.",
                impact = "Enable only after verifying that the economy provider supports region threads.")
        public boolean allowEconomyOnFolia = false;
    }
}
