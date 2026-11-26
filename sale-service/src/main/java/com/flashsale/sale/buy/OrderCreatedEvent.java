package com.flashsale.sale.buy;

/** Kafka payload on order-created. {@code userId} is the buyer's Keycloak subject (a UUID). */
public record OrderCreatedEvent(String orderToken, long saleId, String userId) {
}
