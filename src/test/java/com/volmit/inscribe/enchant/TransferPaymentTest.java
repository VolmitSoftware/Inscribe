package com.volmit.inscribe.enchant;

import art.arcane.volmlib.integration.VaultEconomy;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.inscribe.config.InscribeConfig;
import com.volmit.inscribe.config.RuntimeConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferPaymentTest {
    @Test
    void oversizedExperienceChargeCannotReachEconomyOrMutateItems() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable apply = mock(Runnable.class);
        try (MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class)) {
            String result = new TransferPayment(plugin).execute(player, config(),
                    new EnchantmentPlan.Cost(4_294_967_294_000_000L, 5), () -> true, apply, () -> { });
            assertThat(result).isEqualTo("transfer.unaffordable");
            verify(economies.constructed().getFirst(), never()).withdraw(any(), anyDouble(), anyString());
            verify(apply, never()).run();
        }
    }

    @Test
    void failedWithdrawalCannotMutateItemsOrExperience() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable apply = mock(Runnable.class);
        Runnable rollback = mock(Runnable.class);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class, (economy, context) -> {
                 when(economy.isAvailable()).thenReturn(true);
                 when(economy.withdraw(any(), anyDouble(), anyString())).thenReturn(
                         VaultEconomy.ChargeResult.failed(VaultEconomy.ChargeStatus.TRANSACTION_FAILED, "provider failure"));
             })) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(10, 5),
                    () -> true, apply, rollback);
            assertThat(result).isEqualTo("transfer.reconciliation");
            verify(apply, never()).run();
            verify(rollback, never()).run();
            verify(player, never()).setLevel(anyInt());
        }
    }

    @Test
    void inventoryFailureRestoresExperienceAndRefundsSameCharge() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable rollback = mock(Runnable.class);
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        when(charge.refund()).thenReturn(true);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = successfulEconomy(charge)) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(10, 5),
                    () -> true, () -> { throw new IllegalStateException("Inventory update failed"); }, rollback);
            assertThat(result).isEqualTo("transfer.failed");
            verify(rollback).run();
            verify(player, times(2)).setLevel(10);
            verify(player).setExp(4F / 27F);
            verify(player).setTotalExperience(89);
            verify(player).setExp(0.5F);
            verify(player).setTotalExperience(99);
            verify(charge).refund();
            verify(charge, never()).commit();
        }
    }

    @Test
    void changedInputsAfterWithdrawalRefundBeforeAnyItemMutation() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable apply = mock(Runnable.class);
        Runnable rollback = mock(Runnable.class);
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        when(charge.refund()).thenReturn(true);
        AtomicInteger validations = new AtomicInteger();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = successfulEconomy(charge)) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(10, 5),
                    () -> validations.incrementAndGet() != 2, apply, rollback);
            assertThat(result).isEqualTo("transfer.changed");
            verify(apply, never()).run();
            verify(rollback, never()).run();
            verify(charge).refund();
            verify(player, never()).setLevel(anyInt());
        }
    }

    @Test
    void foliaMoneyRequiresExplicitOptInBeforeProviderAccess() {
        JavaPlugin plugin = plugin();
        Player player = player();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class)) {
            scheduler.when(() -> FoliaScheduler.isFolia(plugin)).thenReturn(true);
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(0, 5),
                    () -> true, () -> { }, () -> { });
            assertThat(result).isEqualTo("transfer.economy");
            verify(economies.constructed().getFirst(), never()).withdraw(any(), anyDouble(), anyString());
        }
    }

    @Test
    void economyCallbackCannotSpendExperienceReservedForMixedPayment() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable apply = mock(Runnable.class);
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        when(charge.refund()).thenReturn(true);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class, (economy, context) -> {
                 when(economy.isAvailable()).thenReturn(true);
                 when(economy.withdraw(any(), anyDouble(), anyString())).thenAnswer(invocation -> {
                     when(player.getLevel()).thenReturn(0);
                     when(player.getExp()).thenReturn(0F);
                     return VaultEconomy.ChargeResult.success(charge);
                 });
             })) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(10, 5),
                    () -> true, apply, () -> { });
            assertThat(result).isEqualTo("transfer.changed");
            verify(apply, never()).run();
            verify(charge).refund();
            verify(player, never()).setLevel(anyInt());
        }
    }

    @Test
    void moneyAndFreeTransfersIgnoreExperienceAboveTheIntegerPointRange() {
        JavaPlugin plugin = plugin();
        Player player = player();
        when(player.getLevel()).thenReturn(30_000);
        Runnable apply = mock(Runnable.class);
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = successfulEconomy(charge)) {
            TransferPayment payment = new TransferPayment(plugin);
            assertThat(payment.execute(player, config(), new EnchantmentPlan.Cost(0, 5), () -> true, apply, () -> { })).isNull();
            assertThat(payment.execute(player, config(), new EnchantmentPlan.Cost(0, 0), () -> true, apply, () -> { })).isNull();
            assertThat(payment.execute(player, config(), new EnchantmentPlan.Cost(100, 0), () -> true, apply, () -> { })).isNull();
            verify(apply, times(3)).run();
            verify(charge).commit();
            verify(player).setLevel(30_000);
            verify(player).setTotalExperience(0);
        }
    }

    @Test
    void changedInputsBeforePaymentReportChangeWithoutCharging() {
        JavaPlugin plugin = plugin();
        Player player = player();
        Runnable apply = mock(Runnable.class);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = successfulEconomy(mock(VaultEconomy.Charge.class))) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(10, 5),
                    () -> false, apply, () -> { });
            assertThat(result).isEqualTo("transfer.changed");
            verify(economies.constructed().getFirst(), never()).withdraw(any(), anyDouble(), anyString());
            verify(apply, never()).run();
        }
    }

    @Test
    void failedRefundAfterChangedInputsRequestsReconciliation() {
        JavaPlugin plugin = plugin();
        Player player = player();
        VaultEconomy.Charge charge = mock(VaultEconomy.Charge.class);
        when(charge.refund()).thenReturn(false);
        AtomicInteger validations = new AtomicInteger();
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = successfulEconomy(charge)) {
            String result = new TransferPayment(plugin).execute(player, config(), new EnchantmentPlan.Cost(0, 5),
                    () -> validations.incrementAndGet() == 1, () -> { }, () -> { });
            assertThat(result).isEqualTo("transfer.reconciliation");
            verify(charge, never()).commit();
        }
    }

    @Test
    void repeatedChargesRemoveExactPointsAcrossLevelFormulaBoundaries() {
        JavaPlugin plugin = plugin();
        AtomicInteger level = new AtomicInteger(32);
        AtomicReference<Float> progress = new AtomicReference<>(0F);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLevel()).thenAnswer(invocation -> level.get());
        when(player.getExp()).thenAnswer(invocation -> progress.get());
        when(player.getTotalExperience()).thenReturn(1628);
        doAnswer(invocation -> {
            level.set(invocation.getArgument(0));
            return null;
        }).when(player).setLevel(anyInt());
        doAnswer(invocation -> {
            progress.set(invocation.getArgument(0));
            return null;
        }).when(player).setExp(anyFloat());
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedConstruction<VaultEconomy> economies = mockConstruction(VaultEconomy.class)) {
            TransferPayment payment = new TransferPayment(plugin);
            long points = TransferPayment.points(32, 0F);
            for (int cost : new int[]{1, 120, 1, 1, 1150, 1, 1, 1, 0, 20, 332}) {
                assertThat(payment.execute(player, config(), new EnchantmentPlan.Cost(cost, 0), () -> true, () -> { }, () -> { })).isNull();
                points -= cost;
                assertThat(TransferPayment.points(level.get(), progress.get())).isEqualTo(points);
                assertThat(progress.get()).isLessThan(1F);
            }
            assertThat(level.get()).isZero();
            assertThat(progress.get()).isZero();
        }
    }

    private MockedConstruction<VaultEconomy> successfulEconomy(VaultEconomy.Charge charge) {
        return mockConstruction(VaultEconomy.class, (economy, context) -> {
            when(economy.isAvailable()).thenReturn(true);
            when(economy.withdraw(any(), anyDouble(), anyString())).thenReturn(VaultEconomy.ChargeResult.success(charge));
        });
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("InscribePaymentTest"));
        return plugin;
    }

    private Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLevel()).thenReturn(10);
        when(player.getExp()).thenReturn(0.5F);
        when(player.getTotalExperience()).thenReturn(99);
        return player;
    }

    private RuntimeConfig config() {
        return RuntimeConfig.from(new InscribeConfig());
    }
}
