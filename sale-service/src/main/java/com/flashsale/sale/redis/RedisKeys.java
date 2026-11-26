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

    /** Present ("1") when the sale is sold through the waiting room; set by order-service's warm-up. */
    public static String gate(long saleId) {
        return "sale:" + saleId + ":gate";
    }

    /** Sorted set of user ids waiting to be let in, lowest score first. */
    public static String queue(long saleId) {
        return "sale:" + saleId + ":queue";
    }

    /** Set of user ids the admission job has let in. */
    public static String admitted(long saleId) {
        return "sale:" + saleId + ":admitted";
    }

    /** Per-second tick that lets exactly one instance admit a batch in that second. */
    public static String admitTick(long saleId, long epochSecond) {
        return "sale:" + saleId + ":admit:" + epochSecond;
    }

    /** Ids of the sales that currently have a waiting room (maintained by order-service). */
    public static final String GATED_SALES = "sale:gated";

    public static String rateLimit(java.util.UUID userId, long epochSecond) {
        return "rl:" + userId + ":" + epochSecond;
    }
}
