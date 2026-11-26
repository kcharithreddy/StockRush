package com.flashsale.order.redis;

/** Redis key names, see ARCHITECTURE.md §6. Must match sale-service. */
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

    public static String gate(long saleId) {
        return "sale:" + saleId + ":gate";
    }

    public static String queue(long saleId) {
        return "sale:" + saleId + ":queue";
    }

    public static String admitted(long saleId) {
        return "sale:" + saleId + ":admitted";
    }

    /** Ids of the sales that currently have a waiting room; sale-service's admission job reads it. */
    public static final String GATED_SALES = "sale:gated";
}
