package com.flashsale.order.admin;

import com.flashsale.order.domain.FlashSale;
import com.flashsale.order.domain.FlashSaleRepository;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.PurchaseOrderRepository;
import com.flashsale.order.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;

@Service
public class SaleAdminService {

    private static final Logger log = LoggerFactory.getLogger(SaleAdminService.class);

    private final FlashSaleRepository sales;
    private final PurchaseOrderRepository orders;
    private final StringRedisTemplate redis;

    public SaleAdminService(FlashSaleRepository sales, PurchaseOrderRepository orders, StringRedisTemplate redis) {
        this.sales = sales;
        this.orders = orders;
        this.redis = redis;
    }

    /**
     * Loads the sale into Redis: stock = total_stock − orders still holding a unit
     * (PENDING_PAYMENT or PAID), plus the active window "startMillis,endMillis".
     */
    @Transactional(readOnly = true)
    public WarmupResponse warmup(long saleId) {
        FlashSale sale = findSale(saleId);
        long held = orders.countBySaleIdAndStatusIn(saleId,
                EnumSet.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID));
        long stock = Math.max(0, sale.getTotalStock() - held);

        redis.opsForValue().set(RedisKeys.stock(saleId), Long.toString(stock));
        redis.opsForValue().set(RedisKeys.window(saleId),
                sale.getStartsAt().toEpochMilli() + "," + sale.getEndsAt().toEpochMilli());
        if (sale.hasWaitingRoom()) {
            redis.opsForValue().set(RedisKeys.gate(saleId), "1");
            redis.opsForSet().add(RedisKeys.GATED_SALES, Long.toString(saleId));
        } else {
            redis.delete(RedisKeys.gate(saleId));
            redis.opsForSet().remove(RedisKeys.GATED_SALES, Long.toString(saleId));
        }

        log.info("Warmed up sale {}: total={}, held={}, stock={}, waitingRoom={}", saleId, sale.getTotalStock(), held, stock, sale.hasWaitingRoom());
        return new WarmupResponse(saleId, stock);
    }

    /** TEST ONLY: wipes the sale's orders and Redis state, then warms it up again. */
    @Transactional
    public WarmupResponse reset(long saleId) {
        findSale(saleId);
        int deleted = orders.deleteBySaleId(saleId);
        redis.delete(List.of(RedisKeys.stock(saleId), RedisKeys.buyers(saleId),
                RedisKeys.queue(saleId), RedisKeys.admitted(saleId)));
        log.warn("Reset sale {}: deleted {} orders and Redis stock, buyers and waiting room", saleId, deleted);
        return warmup(saleId);
    }

    private FlashSale findSale(long saleId) {
        return sales.findById(saleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sale " + saleId));
    }
}
