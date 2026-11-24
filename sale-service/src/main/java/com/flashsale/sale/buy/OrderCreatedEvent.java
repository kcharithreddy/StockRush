package com.flashsale.sale.buy;

public record OrderCreatedEvent(String orderToken, long saleId, long userId) {
}
