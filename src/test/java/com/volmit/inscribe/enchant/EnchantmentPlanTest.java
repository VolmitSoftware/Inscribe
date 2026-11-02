package com.volmit.inscribe.enchant;

import com.volmit.inscribe.config.Currency;
import com.volmit.inscribe.config.EnchantmentPolicy;
import com.volmit.inscribe.config.InscribeConfig;
import com.volmit.inscribe.config.RuntimeConfig;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnchantmentPlanTest {
    private InscribeConfig config;

    @BeforeEach
    void start() {
        MockBukkit.mock();
        config = new InscribeConfig();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void usesStoredEnchantmentsAndNeverDowngrades() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        ItemStack book = book(Map.of(Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 2));
        assertThat(EnchantmentPlan.applicable(sword, book, snapshot()))
                .containsExactly(Map.entry(Enchantment.UNBREAKING, 2));
    }

    @Test
    void unsafeLevelsDoNotBypassConflictsOrMaterialCompatibility() {
        config.transfers.allowUnsafe = true;
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        ItemStack book = book(Map.of(Enchantment.SMITE, 10, Enchantment.FORTUNE, 10, Enchantment.UNBREAKING, 10));
        assertThat(EnchantmentPlan.applicable(sword, book, snapshot()))
                .containsExactly(Map.entry(Enchantment.UNBREAKING, 10));
    }

    @Test
    void leavesAboveMaximumLevelsOnTheBookAndHonorsDisabledPolicies() {
        config.enchantments.put("minecraft:unbreaking", new EnchantmentPolicy(false, 0, false, Currency.EXPERIENCE));
        assertThat(EnchantmentPlan.applicable(new ItemStack(Material.DIAMOND_SWORD),
                book(Map.of(Enchantment.SHARPNESS, 10, Enchantment.LOOTING, 3, Enchantment.UNBREAKING, 3)), snapshot()))
                .containsExactly(Map.entry(Enchantment.LOOTING, 3));
    }

    @Test
    void defaultPoliciesKeepCursesOnTheirItems() {
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        helmet.addUnsafeEnchantment(Enchantment.BINDING_CURSE, 1);
        helmet.addUnsafeEnchantment(Enchantment.VANISHING_CURSE, 1);
        helmet.addUnsafeEnchantment(Enchantment.PROTECTION, 4);
        assertThat(EnchantmentPlan.extractable(helmet, snapshot())).containsExactly(Map.entry(Enchantment.PROTECTION, 4));
    }

    @Test
    void equalLevelsMergeIntoTheNextLevelUpToTheMaximum() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        sword.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        sword.addUnsafeEnchantment(Enchantment.LOOTING, 1);
        ItemStack book = book(Map.of(Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 3, Enchantment.LOOTING, 2));
        assertThat(EnchantmentPlan.applicable(sword, book, snapshot()))
                .containsOnly(Map.entry(Enchantment.SHARPNESS, 4), Map.entry(Enchantment.LOOTING, 2));
        config.transfers.allowUnsafe = true;
        assertThat(EnchantmentPlan.applicable(sword, book, snapshot()))
                .containsOnly(Map.entry(Enchantment.SHARPNESS, 4), Map.entry(Enchantment.LOOTING, 2));
        config.transfers.mergeEqualLevels = false;
        assertThat(EnchantmentPlan.applicable(sword, book, snapshot()))
                .containsOnly(Map.entry(Enchantment.LOOTING, 2));
    }

    @Test
    void repairCostFollowsTheAnvilFormula() {
        assertThat(EnchantmentPlan.increasedRepairCost(0, 0)).isEqualTo(1);
        assertThat(EnchantmentPlan.increasedRepairCost(1, 0)).isEqualTo(3);
        assertThat(EnchantmentPlan.increasedRepairCost(3, 7)).isEqualTo(15);
        assertThat(EnchantmentPlan.increasedRepairCost(Integer.MAX_VALUE, 0)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void totalsSeparateCurrenciesAndSupportsFixedAndPerLevelPrices() {
        config.enchantments.put("minecraft:sharpness", new EnchantmentPolicy(true, 4, true, Currency.EXPERIENCE));
        config.enchantments.put("minecraft:unbreaking", new EnchantmentPolicy(true, 2.5, false, Currency.MONEY));
        EnchantmentPlan.Cost cost = EnchantmentPlan.extractionCost(Map.of(Enchantment.SHARPNESS, 3, Enchantment.UNBREAKING, 2), snapshot());
        assertThat(cost.experience()).isEqualTo(12);
        assertThat(cost.money()).isEqualTo(2.5);
    }

    @Test
    void extremeEnchantmentLevelsKeepExactExperiencePrices() {
        config.pricing.extractionCost = 1_000_000;
        config.pricing.extractionCostPerLevel = true;
        config.pricing.applicationCost = 1_000_000;
        config.pricing.applicationCostPerLevel = true;
        Map<Enchantment, Integer> selected = Map.of(Enchantment.SHARPNESS, Integer.MAX_VALUE,
                Enchantment.UNBREAKING, Integer.MAX_VALUE);
        assertThat(EnchantmentPlan.extractionCost(selected, snapshot()).experience()).isEqualTo(4_294_967_294_000_000L);
        assertThat(EnchantmentPlan.applicationCost(selected, snapshot()).experience()).isEqualTo(4_294_967_294_000_000L);
    }

    @Test
    void experienceUsesCurrentLevelProgressAtFormulaBoundaries() {
        assertThat(TransferPayment.points(0, 0)).isZero();
        assertThat(TransferPayment.points(16, 0)).isEqualTo(352);
        assertThat(TransferPayment.points(17, 0)).isEqualTo(394);
        assertThat(TransferPayment.points(31, 0)).isEqualTo(1507);
        assertThat(TransferPayment.points(32, 0)).isEqualTo(1628);
        assertThat(TransferPayment.points(10, 0.5F)).isEqualTo(174);
        assertThatThrownBy(() -> TransferPayment.points(10, Float.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TransferPayment.points(21_863, 0)).isEqualTo(2_147_407_943L);
        assertThat(TransferPayment.points(21_864, 0)).isGreaterThan(Integer.MAX_VALUE);
        assertThat(TransferPayment.points(Integer.MAX_VALUE, 1)).isEqualTo(Long.MAX_VALUE);
    }

    private RuntimeConfig snapshot() {
        return RuntimeConfig.from(config);
    }

    static ItemStack book(Map<Enchantment, Integer> enchants) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            meta.addStoredEnchant(entry.getKey(), entry.getValue(), true);
        }
        item.setItemMeta(meta);
        return item;
    }
}
