package com.flashsale.sale.buy;

/** Outcome of a buy attempt; {@code orderToken} is set only on SUCCESS. */
public record BuyResult(BuyOutcome outcome, String orderToken) {

    public static BuyResult of(BuyOutcome outcome) {
        return new BuyResult(outcome, null);
    }

    public static BuyResult success(String orderToken) {
        return new BuyResult(BuyOutcome.SUCCESS, orderToken);
    }
}
