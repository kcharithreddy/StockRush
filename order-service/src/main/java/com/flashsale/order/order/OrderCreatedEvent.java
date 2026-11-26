package com.flashsale.order.order;

/** Kafka payload on order-created. Must match sale-service's OrderCreatedEvent; userId is a Keycloak subject (UUID). */
public record OrderCreatedEvent(String orderToken, long saleId, String userId) {
}
