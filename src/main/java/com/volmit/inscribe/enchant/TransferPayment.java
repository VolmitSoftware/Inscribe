package com.volmit.inscribe.enchant;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.inscribe.config.RuntimeConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.BooleanSupplier;
import java.util.logging.Level;

final class TransferPayment {
    private final JavaPlugin plugin;
    private final VaultEconomy economy;

    TransferPayment(JavaPlugin plugin) {
        this.plugin = plugin;
        economy = new VaultEconomy(plugin);
    }

    String execute(Player player, RuntimeConfig config, EnchantmentPlan.Cost cost, BooleanSupplier valid, Runnable apply, Runnable rollback) {
        long experience = cost.experience();
        if (experience > Integer.MAX_VALUE || experience > 0 && points(player.getLevel(), player.getExp()) < experience) {
            return "transfer.unaffordable";
        }
        if (cost.money() > 0 && (FoliaScheduler.isFolia(plugin) && !config.allowEconomyOnFolia()
                || !economy.isAvailable())) {
            return "transfer.economy";
        }
        VaultEconomy.Charge charge = null;
        int level = player.getLevel();
        float progress = player.getExp();
        int total = player.getTotalExperience();
        boolean changed = false;
        try {
            if (!valid.getAsBoolean()) {
                return "transfer.changed";
            }
            if (cost.money() > 0) {
                VaultEconomy.ChargeResult result = economy.withdraw(player, cost.money(), "Inscribe " + player.getUniqueId());
                if (!result.successful()) {
                    return result.status() == VaultEconomy.ChargeStatus.INSUFFICIENT_FUNDS
                            ? "transfer.unaffordable" : "transfer.reconciliation";
                }
                charge = result.charge();
            }
            if (!valid.getAsBoolean() || experience > 0 && points(player.getLevel(), player.getExp()) < experience) {
                return settle(player, charge, "transfer.changed");
            }
            level = player.getLevel();
            progress = player.getExp();
            total = player.getTotalExperience();
            changed = true;
            if (experience > 0) {
                long remaining = points(level, progress) - experience;
                int remainingLevel = level(remaining);
                long base = points(remainingLevel, 0F);
                player.setLevel(remainingLevel);
                player.setExp(Math.min(Math.nextDown(1F), (float) ((double) (remaining - base) / next(remainingLevel))));
                player.setTotalExperience((int) Math.max(0L, Math.min(Integer.MAX_VALUE, total - experience)));
            }
            apply.run();
            if (charge != null) {
                charge.commit();
            }
            return null;
        } catch (RuntimeException exception) {
            if (changed) {
                try {
                    rollback.run();
                    player.setLevel(level);
                    player.setExp(progress);
                    player.setTotalExperience(total);
                } catch (RuntimeException failure) {
                    exception.addSuppressed(failure);
                }
            }
            plugin.getLogger().log(Level.SEVERE, "Enchantment transfer failed for " + player.getUniqueId(), exception);
            return settle(player, charge, "transfer.failed");
        }
    }

    static long points(int level, float progress) {
        if (level < 0 || !Float.isFinite(progress) || progress < 0 || progress > 1) {
            throw new IllegalArgumentException("Player experience is outside the supported range");
        }
        long value = level;
        try {
            long base;
            long next;
            if (level <= 16) {
                base = value * value + 6 * value;
            } else if (level <= 31) {
                base = (5 * value * value - 81 * value + 720) / 2;
            } else {
                base = (Math.multiplyExact(9 * value, value) - 325 * value + 4440) / 2;
            }
            next = next(level);
            return Math.addExact(base, Math.round((double) progress * next));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    static int level(long points) {
        int low = 0;
        int high = Integer.MAX_VALUE;
        while (low < high) {
            int middle = (int) (((long) low + high + 1) >>> 1);
            if (points(middle, 0F) <= points) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private static long next(int level) {
        long value = level;
        return level <= 15 ? 2 * value + 7 : level <= 30 ? 5 * value - 38 : 9 * value - 158;
    }

    private String settle(Player player, VaultEconomy.Charge charge, String status) {
        if (charge != null && !charge.refund()) {
            plugin.getLogger().severe("Inscribe refund requires reconciliation for " + player.getUniqueId());
            return "transfer.reconciliation";
        }
        return status;
    }
}
