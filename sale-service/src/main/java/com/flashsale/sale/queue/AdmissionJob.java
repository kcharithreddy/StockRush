package com.flashsale.sale.queue;

import com.flashsale.sale.redis.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * Every second, lets the next batch of each waiting room in. Runs on every sale-service instance;
 * admit.lua's per-second tick key means only one of them admits in any given second.
 */
@Component
public class AdmissionJob {

    private static final Logger log = LoggerFactory.getLogger(AdmissionJob.class);

    private final StringRedisTemplate redis;
    private final RedisScript<Long> admitScript;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int batchSize;

    public AdmissionJob(StringRedisTemplate redis, @Qualifier("admitScript") RedisScript<Long> admitScript,
                        MeterRegistry meters, Clock clock,
                        @Value("${flashsale.waiting-room.admit-per-second:200}") int batchSize) {
        this.redis = redis;
        this.admitScript = admitScript;
        this.meters = meters;
        this.clock = clock;
        this.batchSize = batchSize;
        meters.counter("flashsale.waiting.room.admitted");
    }

    /** Scheduled by {@link AdmissionScheduler}; tests call it directly. */
    public void admit() {
        try {
            Set<String> sales = redis.opsForSet().members(RedisKeys.GATED_SALES);
            if (sales == null) {
                return;
            }
            long second = clock.millis() / 1000;
            for (String id : sales) {
                long saleId = Long.parseLong(id);
                Long admitted = redis.execute(admitScript,
                        List.of(RedisKeys.gate(saleId), RedisKeys.queue(saleId), RedisKeys.admitted(saleId),
                                RedisKeys.window(saleId), RedisKeys.stock(saleId), RedisKeys.admitTick(saleId, second)),
                        Integer.toString(batchSize));
                if (admitted != null && admitted > 0) {
                    meters.counter("flashsale.waiting.room.admitted").increment(admitted);
                    log.info("Admitted {} buyer(s) to sale {}", admitted, saleId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Admission run failed; next tick will retry: {}", e.getMessage());
        }
    }
}
