package com.volmit.inscribe.config;

public record EnchantmentPolicy(boolean enabled, double cost, boolean perLevel, Currency currency) {
    public static final int MAX_COST = 1_000_000;

    EnchantmentPolicy validated(String path) {
        if (currency == null) {
            throw new IllegalArgumentException(path + ".currency must be EXPERIENCE or MONEY");
        }
        if (!Double.isFinite(cost) || cost < 0 || cost > MAX_COST) {
            throw new IllegalArgumentException(path + ".cost must be between 0 and " + MAX_COST);
        }
        if (currency == Currency.EXPERIENCE && cost != Math.rint(cost)) {
            throw new IllegalArgumentException(path + ".cost must be a whole number of experience points");
        }
        return this;
    }
}
