package com.flashsale.order;

import com.flashsale.order.order.InvalidEventException;
import com.flashsale.order.order.OrderConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConsumerTest extends IntegrationTest {

    @Autowired
    OrderConsumer consumer;

    private static String event(String token, long saleId, long userId) {
        return "{\"orderToken\":\"" + token + "\",\"saleId\":" + saleId + ",\"userId\":" + userId + "}";
    }

    @Test
    void createsPendingOrder() {
        String token = UUID.randomUUID().toString();

        consumer.onOrderCreated(event(token, 1, 42));

        assertThat(statusOf(UUID.fromString(token))).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void redeliveredEventIsIgnored() {
        String payload = event(UUID.randomUUID().toString(), 1, 42);

        consumer.onOrderCreated(payload);
        consumer.onOrderCreated(payload);

        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void secondOrderForSameUserAndSaleIsIgnored() {
        consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, 42));
        consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, 42));

        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void malformedEventsAreRejectedWithoutWriting() {
        assertThatThrownBy(() -> consumer.onOrderCreated("not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated("")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event("not-a-uuid", 1, 42)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 0, 42)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, -1)))
                .isInstanceOf(InvalidEventException.class);

        assertThat(countOrders()).isZero();
    }

    @Test
    void unknownSaleIsANonRetryableIntegrityError() {
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 999, 42)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
