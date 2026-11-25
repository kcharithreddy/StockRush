package com.flashsale.order.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 10 s, cancels unpaid orders past the payment timeout (ARCHITECTURE.md §10).
 * Switched off with flashsale.jobs.enabled=false; a failed run is logged and retried on the next tick.
 */
@Component
@ConditionalOnProperty(name = "flashsale.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentTimeoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutWorker.class);

    private final OrderExpiryService expiry;

    public PaymentTimeoutWorker(OrderExpiryService expiry) {
        this.expiry = expiry;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    public void run() {
        try {
            expiry.expireOverdue();
        } catch (RuntimeException e) {
            log.error("Payment timeout run failed; will retry on the next tick", e);
        }
    }
}
