package com.flashsale.order.redis;

/** Redis key names, see CLAUDE.md §6. Must match sale-service. */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String stock(long saleId) {
        return "sale:" + saleId + ":stock";
    }

    public static String buyers(long saleId) {
        return "sale:" + saleId + ":buyers";
    }

    public static String window(long saleId) {
        return "sale:" + saleId + ":window";
    }
}
