package com.flashsale.order;

import com.flashsale.order.jobs.OrderExpiryService;
import com.flashsale.order.jobs.ReconciliationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobsTest extends IntegrationTest {

    @Autowired
    OrderExpiryService expiry;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    MeterRegistry meters;

    @Test
    void cancelsOnlyOverdueUnpaidOrdersAndReturnsTheirStock() {
        redis.opsForValue().set("sale:1:stock", "0");
        UUID overdue = insertOrder(1, 1, "PENDING_PAYMENT", 20);
        UUID overdue2 = insertOrder(1, 2, "PENDING_PAYMENT", 11);
        UUID fresh = insertOrder(1, 3, "PENDING_PAYMENT", 2);
        UUID paid = insertOrder(1, 4, "PAID", 30);
        double expiredBefore = meters.get("flashsale.orders.expired").counter().count();

        assertThat(expiry.expireOverdue()).isEqualTo(2);
        assertThat(meters.get("flashsale.orders.expired").counter().count()).isEqualTo(expiredBefore + 2);

        assertThat(statusOf(overdue)).isEqualTo("CANCELLED");
        assertThat(statusOf(overdue2)).isEqualTo("CANCELLED");
        assertThat(statusOf(fresh)).isEqualTo("PENDING_PAYMENT");
        assertThat(statusOf(paid)).isEqualTo("PAID");
        assertThat(redis.opsForValue().get("sale:1:stock")).isEqualTo("2");

        assertThat(expiry.expireOverdue()).isZero();
        assertThat(redis.opsForValue().get("sale:1:stock")).isEqualTo("2");
    }

    @Test
    void reconciliationReportsEachDirection() {
        insertOrder(1, 1, "PENDING_PAYMENT", 0);
        insertOrder(1, 2, "PAID", 0);
        insertOrder(1, 3, "CANCELLED", 0);
        // seeded sale has total_stock 100 → expected 98

        redis.opsForValue().set("sale:1:stock", "98");
        assertThat(reconciliation.reconcile()).singleElement()
                .satisfies(r -> assertThat(r.direction()).isEqualTo("ok"));

        double highBefore = counter("redis_high");
        redis.opsForValue().set("sale:1:stock", "99");
        assertThat(reconciliation.reconcile().get(0).direction()).isEqualTo("redis_high");
        assertThat(counter("redis_high")).isEqualTo(highBefore + 1);

        double lowBefore = counter("redis_low");
        redis.opsForValue().set("sale:1:stock", "90");
        assertThat(reconciliation.reconcile().get(0).direction()).isEqualTo("redis_low");
        assertThat(counter("redis_low")).isEqualTo(lowBefore + 1);
    }

    @Test
    void reconciliationSkipsSaleThatWasNeverWarmedUp() {
        assertThat(reconciliation.reconcile()).isEmpty();
    }

    private double counter(String direction) {
        var c = meters.find("flashsale.reconcile.mismatch").tag("direction", direction).counter();
        return c == null ? 0 : c.count();
    }
}
