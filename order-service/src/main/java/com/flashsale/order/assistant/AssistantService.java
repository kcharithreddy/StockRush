package com.flashsale.order.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ops assistant: turns an {@link OpsFacts} snapshot into a short status report with an AI model.
 * Off unless an API key is configured; at most one call per min-interval; sends aggregates only.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final String INSTRUCTIONS = """
            You are the on-call assistant for StockRush, a flash-sale backend. Stock lives in Redis and is \
            decremented atomically by a Lua script. Each successful buy publishes an order event to Kafka, and \
            order-service writes the order to PostgreSQL as PENDING_PAYMENT. Unpaid orders become CANCELLED after \
            the payment timeout and their unit goes back to Redis.

            You get a JSON snapshot of the system. Field notes:
            - sales[].unaccounted = totalStock - (stockInRedis + PENDING_PAYMENT + PAID). 0 is healthy. A small \
            positive number can be orders still in flight; one that stays positive means lost orders; a negative \
            number means more units were sold than exist (oversell). null means the sale was never warmed up.
            - deadLetters: order events that failed processing and wait on the dead-letter topic, counted by \
            exception class.
            - alerts: Prometheus alerts firing right now.
            - unavailable: data sources that could not be read.

            Write a short report for the shop admin, plain text, under 180 words:
            1. One line: overall status (OK, ATTENTION or CRITICAL).
            2. Each problem found, with its most likely cause.
            3. Concrete next steps. Admin actions available: GET /admin/dead-letters to inspect parked events, \
            POST /admin/dead-letters/replay once the cause is fixed, POST /admin/sales/{id}/warmup to open a sale.
            Use only the snapshot. Say so when something is unknown. Never invent numbers.""";

    private final AssistantProperties properties;
    private final OpsFactsCollector facts;
    private final OpenAiClient ai;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicLong lastCall = new AtomicLong(Long.MIN_VALUE);

    public AssistantService(AssistantProperties properties, OpsFactsCollector facts, OpenAiClient ai,
                            ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.facts = facts;
        this.ai = ai;
        this.json = json;
        this.clock = clock;
    }

    public record Report(String summary, String model, OpsFacts sentToModel) {
    }

    public Report summarize() {
        if (!properties.enabled()) {
            throw new AssistantException.Disabled();
        }
        reserveCall();
        OpsFacts snapshot = facts.collect();
        log.info("Asking {} for an ops summary", properties.model());
        String summary = ai.complete(INSTRUCTIONS, json.writeValueAsString(snapshot));
        return new Report(summary, properties.model(), snapshot);
    }

    /** Claims the next call slot, or throws TooSoon. Failed calls count too, so errors cannot be retried in a loop. */
    private void reserveCall() {
        long now = clock.millis();
        long interval = properties.minInterval().toMillis();
        long previous = lastCall.get();
        if (previous != Long.MIN_VALUE && now - previous < interval) {
            throw new AssistantException.TooSoon(Math.max(1, (interval - (now - previous) + 999) / 1000));
        }
        if (!lastCall.compareAndSet(previous, now)) {
            throw new AssistantException.TooSoon(Math.max(1, (interval + 999) / 1000));
        }
    }
}
