package com.flashsale.order.assistant;

import com.flashsale.order.deadletter.DeadLetterService;
import com.flashsale.order.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds {@link OpsFacts}. Each source is read independently: one that fails is listed under "unavailable". */
@Component
public class OpsFactsCollector {

    private static final Logger log = LoggerFactory.getLogger(OpsFactsCollector.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final DeadLetterService deadLetters;
    private final AlertmanagerClient alertmanager;
    private final Clock clock;

    public OpsFactsCollector(JdbcTemplate jdbc, StringRedisTemplate redis, DeadLetterService deadLetters,
                             AlertmanagerClient alertmanager, Clock clock) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.deadLetters = deadLetters;
        this.alertmanager = alertmanager;
        this.clock = clock;
    }

    public OpsFacts collect() {
        Instant now = clock.instant();
        List<String> unavailable = new ArrayList<>();

        List<OpsFacts.Sale> sales = null;
        try {
            sales = sales(now);
        } catch (RuntimeException e) {
            unavailable.add("sales");
            log.warn("Assistant: could not read sales ({})", e.getClass().getSimpleName());
        }

        DeadLetterService.Summary dlt = null;
        try {
            dlt = deadLetters.summary();
        } catch (RuntimeException e) {
            unavailable.add("deadLetters");
            log.warn("Assistant: could not read the dead-letter topic ({})", e.getClass().getSimpleName());
        }

        List<OpsFacts.Alert> alerts = null;
        try {
            alerts = alertmanager.firing();
        } catch (RuntimeException e) {
            unavailable.add("alerts");
            log.warn("Assistant: could not read Alertmanager ({})", e.getClass().getSimpleName());
        }

        return new OpsFacts(now, sales, dlt, alerts, unavailable.isEmpty() ? null : unavailable);
    }

    private List<OpsFacts.Sale> sales(Instant now) {
        Map<Long, Map<String, Integer>> counts = new HashMap<>();
        jdbc.query("SELECT sale_id, status, count(*) AS n FROM orders GROUP BY sale_id, status", rs -> {
            counts.computeIfAbsent(rs.getLong("sale_id"), id -> new TreeMap<>())
                    .put(rs.getString("status"), rs.getInt("n"));
        });
        return jdbc.query("SELECT id, total_stock, starts_at, ends_at FROM flash_sales ORDER BY id", (rs, i) -> {
            long id = rs.getLong("id");
            int total = rs.getInt("total_stock");
            Map<String, Integer> orders = counts.getOrDefault(id, new TreeMap<>());
            String left = redis.opsForValue().get(RedisKeys.stock(id));
            Integer stock = left == null ? null : Integer.valueOf(left);
            Integer unaccounted = stock == null ? null
                    : total - (stock + orders.getOrDefault("PENDING_PAYMENT", 0) + orders.getOrDefault("PAID", 0));
            return new OpsFacts.Sale(id, state(now, rs.getTimestamp("starts_at"), rs.getTimestamp("ends_at")),
                    total, stock, orders, unaccounted);
        });
    }

    private static String state(Instant now, Timestamp startsAt, Timestamp endsAt) {
        if (now.isBefore(startsAt.toInstant())) {
            return "NOT_STARTED";
        }
        return now.isBefore(endsAt.toInstant()) ? "OPEN" : "ENDED";
    }
}
