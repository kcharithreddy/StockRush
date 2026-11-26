package com.flashsale.order;

import com.flashsale.order.order.InvalidEventException;
import com.flashsale.order.order.OrderConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConsumerTest extends IntegrationTest {

    @Autowired
    OrderConsumer consumer;

    private static final String USER = "00000000-0000-4000-8000-000000000042";

    private static String event(String token, long saleId, String userId) {
        return "{\"orderToken\":\"" + token + "\",\"saleId\":" + saleId + ",\"userId\":\"" + userId + "\"}";
    }

    @Test
    void createsPendingOrder() {
        String token = UUID.randomUUID().toString();

        consumer.onOrderCreated(event(token, 1, USER));

        assertThat(statusOf(UUID.fromString(token))).isEqualTo("PENDING_PAYMENT");
    }

    @Autowired
    MeterRegistry meters;

    @Test
    void redeliveredEventIsIgnored() {
        String payload = event(UUID.randomUUID().toString(), 1, USER);
        double createdBefore = meters.get("flashsale.orders.placed").counter().count();

        consumer.onOrderCreated(payload);
        consumer.onOrderCreated(payload);

        assertThat(countOrders()).isEqualTo(1);
        assertThat(meters.get("flashsale.orders.placed").counter().count()).isEqualTo(createdBefore + 1);
    }

    @Test
    void secondOrderForSameUserAndSaleIsIgnored() {
        consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, USER));
        consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, USER));

        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void malformedEventsAreRejectedWithoutWriting() {
        assertThatThrownBy(() -> consumer.onOrderCreated("not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated("")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event("not-a-uuid", 1, USER)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 0, USER)))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 1, "not-a-user")))
                .isInstanceOf(InvalidEventException.class);

        assertThat(countOrders()).isZero();
    }

    @Test
    void unknownSaleIsANonRetryableIntegrityError() {
        assertThatThrownBy(() -> consumer.onOrderCreated(event(UUID.randomUUID().toString(), 999, USER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
