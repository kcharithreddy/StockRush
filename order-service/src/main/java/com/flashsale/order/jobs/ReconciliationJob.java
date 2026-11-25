package com.flashsale.order.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 60 s, compares Redis stock with PostgreSQL for every active sale (ARCHITECTURE.md §10).
 * Switched off with flashsale.jobs.enabled=false; a failed run is logged and retried on the next tick.
 */
@Component
@ConditionalOnProperty(name = "flashsale.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService reconciliation;

    public ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        try {
            reconciliation.reconcile();
        } catch (RuntimeException e) {
            log.error("Reconciliation run failed; will retry on the next tick", e);
        }
    }
}
