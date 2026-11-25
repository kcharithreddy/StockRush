package com.flashsale.order.jobs;

import com.flashsale.order.order.OrderStore;
import com.flashsale.order.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cancels unpaid orders past the payment timeout and returns their units to Redis stock.
 *
 * <p>Order matters: the cancel is committed first (one atomic UPDATE ... RETURNING), and only then is
 * stock incremented. If the process dies in between, Redis is left one unit LOW, which loses a sale
 * but can never oversell; the reconciliation job reports it as redis_low. Incrementing first would
 * risk the opposite.
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);
    private static final int BATCH_SIZE = 500;

    private final OrderStore orders;
    private final StringRedisTemplate redis;
    private final int timeoutMinutes;

    public OrderExpiryService(OrderStore orders, StringRedisTemplate redis,
                              @Value("${orders.payment-timeout-minutes}") int timeoutMinutes) {
        this.orders = orders;
        this.redis = redis;
        this.timeoutMinutes = timeoutMinutes;
    }

    /** Returns how many orders were cancelled. */
    public int expireOverdue() {
        int total = 0;
        List<Long> saleIds;
        do {
            saleIds = orders.cancelOverdue(timeoutMinutes, BATCH_SIZE);
            for (Long saleId : saleIds) {
                redis.opsForValue().increment(RedisKeys.stock(saleId));
            }
            total += saleIds.size();
        } while (saleIds.size() == BATCH_SIZE);

        if (total > 0) {
            log.info("Cancelled {} unpaid orders older than {} min and returned their stock", total, timeoutMinutes);
        }
        return total;
    }
}
