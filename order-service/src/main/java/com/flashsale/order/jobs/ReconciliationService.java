package com.flashsale.order.jobs;

import com.flashsale.order.domain.FlashSale;
import com.flashsale.order.domain.FlashSaleRepository;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.PurchaseOrderRepository;
import com.flashsale.order.redis.RedisKeys;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Compares Redis stock with what PostgreSQL says it should be, for every sale in its window
 * (ARCHITECTURE.md §10). Only reports: it never corrects stock, because events still in flight
 * make Redis legitimately lower for a few moments.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    static final String METRIC = "flashsale.reconcile.mismatch";

    public record Result(long saleId, long expected, long actual) {
        public String direction() {
            if (actual > expected) {
                return "redis_high";
            }
            return actual < expected ? "redis_low" : "ok";
        }
    }

    private final FlashSaleRepository sales;
    private final PurchaseOrderRepository orders;
    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final Clock clock;

    public ReconciliationService(FlashSaleRepository sales, PurchaseOrderRepository orders,
                                 StringRedisTemplate redis, MeterRegistry meters, Clock clock) {
        this.sales = sales;
        this.orders = orders;
        this.redis = redis;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Result> reconcile() {
        List<Result> results = new ArrayList<>();
        for (FlashSale sale : sales.findActiveAt(clock.instant())) {
            String stock = redis.opsForValue().get(RedisKeys.stock(sale.getId()));
            if (stock == null) {
                log.warn("Sale {} is active but has no stock in Redis; run warm-up", sale.getId());
                continue;
            }
            long held = orders.countBySaleIdAndStatusIn(sale.getId(),
                    EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID));
            Result result = new Result(sale.getId(), sale.getTotalStock() - held, Long.parseLong(stock));
            report(result);
            results.add(result);
        }
        return results;
    }

    private void report(Result r) {
        switch (r.direction()) {
            case "redis_high" -> log.error("OVERSELL RISK in sale {}: Redis stock {} > expected {}",
                    r.saleId(), r.actual(), r.expected());
            case "redis_low" -> log.warn("Sale {}: Redis stock {} < expected {} (events in flight or lost)",
                    r.saleId(), r.actual(), r.expected());
            default -> {
                return;
            }
        }
        meters.counter(METRIC, "direction", r.direction()).increment();
    }
}
