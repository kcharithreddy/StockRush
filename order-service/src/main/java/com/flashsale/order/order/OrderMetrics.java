package com.flashsale.order.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Order lifecycle counters (ARCHITECTURE.md §13): flashsale_orders_{placed,paid,expired}_total.
 * "placed", not "created": the Prometheus client strips a trailing _created from names
 * (an OpenMetrics reserved suffix), so flashsale_orders_created_total cannot exist.
 */
@Component
public class OrderMetrics {

    private final Counter created;
    private final Counter paid;
    private final Counter expired;

    public OrderMetrics(MeterRegistry meters) {
        this.created = Counter.builder("flashsale.orders.placed")
                .description("Orders inserted from order-created events (duplicates excluded)")
                .register(meters);
        this.paid = Counter.builder("flashsale.orders.paid")
                .description("Orders moved to PAID")
                .register(meters);
        this.expired = Counter.builder("flashsale.orders.expired")
                .description("Unpaid orders cancelled by the payment timeout")
                .register(meters);
    }

    public void created() {
        created.increment();
    }

    public void paid() {
        paid.increment();
    }

    public void expired(int count) {
        expired.increment(count);
    }
}
