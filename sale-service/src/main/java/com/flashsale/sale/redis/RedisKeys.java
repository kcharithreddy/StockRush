package com.flashsale.sale.redis;

/** Redis key names, see ARCHITECTURE.md §6. Must match order-service. */
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

    public static String rateLimit(java.util.UUID userId, long epochSecond) {
        return "rl:" + userId + ":" + epochSecond;
    }
}
