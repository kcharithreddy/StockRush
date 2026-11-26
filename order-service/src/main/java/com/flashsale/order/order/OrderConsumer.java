package com.flashsale.order.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Turns order-created events into PENDING_PAYMENT rows. Delivery is at-least-once, so the insert
 * is idempotent (ON CONFLICT DO NOTHING). Malformed events are rejected without retrying;
 * database errors are retried with backoff by {@link KafkaConfig#kafkaErrorHandler()}.
 */
@Component
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final OrderStore orders;
    private final ObjectMapper json;
    private final OrderMetrics metrics;

    public OrderConsumer(OrderStore orders, ObjectMapper json, OrderMetrics metrics) {
        this.orders = orders;
        this.json = json;
        this.metrics = metrics;
    }

    @KafkaListener(topics = KafkaConfig.ORDER_CREATED_TOPIC, concurrency = "3")
    public void onOrderCreated(String payload) {
        OrderCreatedEvent event = parse(payload);
        UUID token = parseUuid(event.orderToken(), "order token");
        UUID userId = parseUuid(event.userId(), "user id");

        if (orders.insertPending(token, event.saleId(), userId)) {
            metrics.created();
            log.info("Order created in sale {}", event.saleId());
        } else {
            log.info("Duplicate order-created event ignored for sale {}", event.saleId());
        }
    }

    private OrderCreatedEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("Empty order-created payload");
        }
        OrderCreatedEvent event;
        try {
            event = json.readValue(payload, OrderCreatedEvent.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Malformed order-created payload", e);
        }
        if (event.saleId() <= 0) {
            throw new InvalidEventException("Invalid sale id");
        }
        return event;
    }

    private static UUID parseUuid(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidEventException("Invalid " + what, e);
        }
    }
}
