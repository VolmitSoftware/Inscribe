package com.volmit.inscribe.enchant;

import com.volmit.inscribe.config.Currency;
import com.volmit.inscribe.config.EnchantmentPolicy;
import com.volmit.inscribe.config.RuntimeConfig;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EnchantmentPlan {
    private EnchantmentPlan() {
    }

    public static Map<Enchantment, Integer> extractable(ItemStack source, RuntimeConfig config) {
        Map<Enchantment, Integer> result = new LinkedHashMap<>();
        source.getEnchantments().entrySet().stream()
                .sorted(Map.Entry.comparingByKey((first, second) -> first.getKey().toString().compareTo(second.getKey().toString())))
                .filter(entry -> config.policy(entry.getKey().getKey().toString()).enabled())
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    public static Map<Enchantment, Integer> applicable(ItemStack source, ItemStack book, RuntimeConfig config) {
        if (book.getType() != Material.ENCHANTED_BOOK || !(book.getItemMeta() instanceof EnchantmentStorageMeta stored)) {
            return Map.of();
        }
        Map<Enchantment, Integer> result = new LinkedHashMap<>();
        Map<Enchantment, Integer> present = new LinkedHashMap<>(source.getEnchantments());
        for (Map.Entry<Enchantment, Integer> entry : stored.getStoredEnchants().entrySet().stream()
                .sorted(Map.Entry.comparingByKey((first, second) -> first.getKey().toString().compareTo(second.getKey().toString()))).toList()) {
            Enchantment enchantment = entry.getKey();
            int current = present.getOrDefault(enchantment, 0);
            int level = entry.getValue() == current && config.mergeEqualLevels() && current < enchantment.getMaxLevel()
                    ? current + 1 : entry.getValue();
            if (level > enchantment.getMaxLevel() && !config.allowUnsafe()
                    || level <= current || !config.policy(enchantment.getKey().toString()).enabled()) {
                continue;
            }
            if (!enchantment.canEnchantItem(source) || conflicts(enchantment, present)) {
                continue;
            }
            result.put(enchantment, level);
            present.put(enchantment, level);
        }
        return result;
    }

    public static int increasedRepairCost(int item, int book) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, Math.max(item, book)) * 2L + 1L);
    }

    public static Cost extractionCost(Map<Enchantment, Integer> selected, RuntimeConfig config) {
        BigDecimal experience = BigDecimal.ZERO;
        BigDecimal money = BigDecimal.ZERO;
        for (Map.Entry<Enchantment, Integer> entry : selected.entrySet()) {
            EnchantmentPolicy policy = config.policy(entry.getKey().getKey().toString());
            BigDecimal amount = BigDecimal.valueOf(Math.max(0, policy.cost()))
                    .multiply(BigDecimal.valueOf(policy.perLevel() ? entry.getValue() : 1));
            if (policy.currency() == Currency.EXPERIENCE) {
                experience = experience.add(amount);
            } else {
                money = money.add(amount);
            }
        }
        return new Cost(experience.setScale(0, RoundingMode.CEILING).longValueExact(), money.doubleValue());
    }

    public static Cost applicationCost(Map<Enchantment, Integer> selected, RuntimeConfig config) {
        long multiplier = config.applicationCostPerLevel() ? selected.values().stream().mapToLong(Integer::longValue).sum() : 1;
        long amount = Math.multiplyExact(config.applicationCost(), multiplier);
        return config.currency() == Currency.EXPERIENCE ? new Cost(amount, 0) : new Cost(0, amount);
    }

    private static boolean conflicts(Enchantment enchantment, Map<Enchantment, Integer> present) {
        for (Enchantment other : present.keySet()) {
            if (!other.equals(enchantment) && (enchantment.conflictsWith(other) || other.conflictsWith(enchantment))) {
                return true;
            }
        }
        return false;
    }

    public record Cost(long experience, double money) {
        public Cost {
            if (experience < 0 || !Double.isFinite(money) || money < 0) {
                throw new IllegalArgumentException("Invalid enchantment transfer price");
            }
        }
    }
}
