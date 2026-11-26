package com.flashsale.order.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.flashsale.order.deadletter.DeadLetterService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The snapshot the assistant may share with the AI provider: aggregates only. It holds no user id, order token,
 * event payload, exception message or alert label other than name and severity. {@code GET /admin/assistant/facts}
 * returns exactly this, so an admin can see what would be sent before anything is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpsFacts(Instant generatedAt, List<Sale> sales, DeadLetterService.Summary deadLetters,
                       List<Alert> alerts, List<String> unavailable) {

    /**
     * @param state        NOT_STARTED, OPEN or ENDED, from the sale's window
     * @param stockInRedis units left in Redis, null if the sale was never warmed up
     * @param orders       order counts by status
     * @param unaccounted  totalStock - (stockInRedis + PENDING_PAYMENT + PAID); 0 is healthy, null if not warmed up
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sale(long saleId, String state, int totalStock, Integer stockInRedis,
                       Map<String, Integer> orders, Integer unaccounted) {
    }

    public record Alert(String name, String severity, String summary, Instant since) {
    }
}
