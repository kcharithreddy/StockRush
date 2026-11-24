package com.flashsale.sale.web;

import com.flashsale.sale.redis.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockController {

    private final StringRedisTemplate redis;

    public StockController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Reads remaining stock from Redis; a sale that was never warmed up reports 0. */
    @GetMapping("/sales/{saleId}/stock")
    public StockResponse stock(@PathVariable long saleId) {
        String value = redis.opsForValue().get(RedisKeys.stock(saleId));
        return new StockResponse(value == null ? 0 : Integer.parseInt(value));
    }
}
