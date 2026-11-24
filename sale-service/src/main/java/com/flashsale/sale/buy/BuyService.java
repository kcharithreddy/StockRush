package com.flashsale.sale.buy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.sale.config.RedisConfig;
import com.flashsale.sale.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The buy path (ARCHITECTURE.md §10). Stock is only ever decremented inside buy.lua. */
@Service
public class BuyService {

    private static final Logger log = LoggerFactory.getLogger(BuyService.class);
    private static final long SEND_TIMEOUT_SECONDS = 2;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> buyScript;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Clock clock;

    /** Sales this instance has seen sell out; lets it answer 410 without a Redis call. */
    private final Set<Long> soldOut = ConcurrentHashMap.newKeySet();

    public BuyService(StringRedisTemplate redis, RedisScript<Long> buyScript,
                      KafkaTemplate<String, String> kafka, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.buyScript = buyScript;
        this.kafka = kafka;
        this.json = json;
        this.clock = clock;
    }

    public BuyResult buy(long saleId, long userId) {
        if (soldOut.contains(saleId)) {
            return BuyResult.of(BuyOutcome.SOLD_OUT);
        }

        Long result = redis.execute(buyScript,
                List.of(RedisKeys.stock(saleId), RedisKeys.buyers(saleId), RedisKeys.window(saleId)),
                Long.toString(userId), Long.toString(clock.millis()));
        if (result == null) {
            throw new IllegalStateException("buy.lua returned no result");
        }
        BuyOutcome outcome = BuyOutcome.fromScriptResult(result);

        if (outcome == BuyOutcome.SOLD_OUT) {
            soldOut.add(saleId);
        }
        if (outcome != BuyOutcome.SUCCESS) {
            return BuyResult.of(outcome);
        }

        String orderToken = UUID.randomUUID().toString();
        return publish(new OrderCreatedEvent(orderToken, saleId, userId));
    }

    private BuyResult publish(OrderCreatedEvent event) {
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            compensate(event);
            throw new IllegalStateException("Could not serialise " + event, e);
        }

        CompletableFuture<SendResult<String, String>> send;
        try {
            send = kafka.send(RedisConfig.ORDER_CREATED_TOPIC, Long.toString(event.userId()), payload);
        } catch (RuntimeException e) {
            // Rejected before leaving the producer (e.g. no broker metadata): definitely not sent.
            log.warn("Kafka rejected {}, compensating", event, e);
            compensate(event);
            return BuyResult.of(BuyOutcome.TRY_AGAIN);
        }

        try {
            send.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return BuyResult.success(event.orderToken());
        } catch (ExecutionException e) {
            // Kafka reported the send as failed: give the unit back.
            log.warn("Kafka send failed for {}, compensating", event, e.getCause());
            compensate(event);
            return BuyResult.of(BuyOutcome.TRY_AGAIN);
        } catch (TimeoutException e) {
            log.error("Kafka send for {} still pending after {} s", event, SEND_TIMEOUT_SECONDS);
            compensateIfEventuallyFails(send, event);
            return BuyResult.of(BuyOutcome.TRY_AGAIN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            compensateIfEventuallyFails(send, event);
            return BuyResult.of(BuyOutcome.TRY_AGAIN);
        }
    }

    /**
     * The send is still in flight, so it may yet succeed and become an order: returning the unit now
     * could sell it twice. Instead, wait for the producer's final answer (bounded by delivery.timeout.ms)
     * and give the unit back only if Kafka finally reports failure.
     */
    private void compensateIfEventuallyFails(CompletableFuture<SendResult<String, String>> send,
                                             OrderCreatedEvent event) {
        send.whenComplete((result, error) -> {
            if (error != null) {
                log.warn("Late Kafka failure for {}, compensating", event, error);
                compensate(event);
            } else {
                log.info("Late Kafka success for {}; order will be created", event);
            }
        });
    }

    private void compensate(OrderCreatedEvent event) {
        redis.opsForValue().increment(RedisKeys.stock(event.saleId()));
        redis.opsForSet().remove(RedisKeys.buyers(event.saleId()), Long.toString(event.userId()));
        soldOut.remove(event.saleId());
    }

    /** Stock can come back (failed sends, payment timeouts), so the local flags are short-lived. */
    @Scheduled(fixedRate = 5000)
    public void clearSoldOutFlags() {
        soldOut.clear();
    }

    boolean isFlaggedSoldOut(long saleId) {
        return soldOut.contains(saleId);
    }
}
