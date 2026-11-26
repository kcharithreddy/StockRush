package com.flashsale.sale.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the admission job every second. Off with flashsale.waiting-room.scheduler-enabled=false (tests). */
@Component
@ConditionalOnProperty(name = "flashsale.waiting-room.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionScheduler {

    private final AdmissionJob job;

    public AdmissionScheduler(AdmissionJob job) {
        this.job = job;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        job.admit();
    }
}
