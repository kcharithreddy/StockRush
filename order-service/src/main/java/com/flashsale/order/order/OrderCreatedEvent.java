package com.flashsale.order.order;

/** Kafka payload on order-created. Must match sale-service's OrderCreatedEvent. */
public record OrderCreatedEvent(String orderToken, long saleId, long userId) {
}
