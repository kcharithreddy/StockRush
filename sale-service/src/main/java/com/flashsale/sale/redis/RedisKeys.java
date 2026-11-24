package com.flashsale.sale.redis;

/** Redis key names, see CLAUDE.md §6. Must match order-service. */
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

    public static String rateLimit(long userId, long epochSecond) {
        return "rl:" + userId + ":" + epochSecond;
    }
}
