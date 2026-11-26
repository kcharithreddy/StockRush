package com.flashsale.sale.buy;

import tools.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class BuyServiceTest {

    private static final long SALE = 1;
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000042");
    private static final String USER_ID = USER.toString();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SetOperations<String, String> sets = mock(SetOperations.class);
    private final RedisScript<Long> script = mock(RedisScript.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

    private BuyService service;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        meters = new SimpleMeterRegistry();
        service = new BuyService(redis, script, kafka, JsonMapper.builder().build(), meters);
    }

    private void luaReturns(long result) {
        when(redis.execute(eq(script), any(List.class), any())).thenReturn(result);
    }

    private void kafkaReturns(CompletableFuture<SendResult<String, String>> future) {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(future);
    }

    @Test
    void successPublishesEventKeyedByUser() {
        luaReturns(1);
        kafkaReturns(CompletableFuture.completedFuture(null));

        BuyResult result = service.buy(SALE, USER);

        assertThat(result.outcome()).isEqualTo(BuyOutcome.SUCCESS);
        assertThat(result.orderToken()).isNotBlank();
        verify(redis).execute(script, List.of("sale:1:stock", "sale:1:buyers", "sale:1:window", "sale:1:gate", "sale:1:admitted"), USER_ID);
        verify(kafka).send(eq("order-created"), eq(USER_ID),
                eq("{\"orderToken\":\"" + result.orderToken() + "\",\"saleId\":1,\"userId\":\"" + USER_ID + "\"}"));
    }

    @Test
    void soldOutSetsLocalFlagSoNextCallSkipsRedis() {
        luaReturns(0);

        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.SOLD_OUT);
        assertThat(service.buy(SALE, UUID.randomUUID()).outcome()).isEqualTo(BuyOutcome.SOLD_OUT);

        verify(redis, times(1)).execute(eq(script), any(List.class), any());
        service.clearSoldOutFlags();
        assertThat(service.isFlaggedSoldOut(SALE)).isFalse();
    }

    @Test
    void alreadyBoughtAndNotActiveDoNotPublish() {
        luaReturns(-1);
        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.ALREADY_BOUGHT);

        luaReturns(-2);
        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.NOT_ACTIVE);

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void failedSendGivesTheUnitBack() {
        luaReturns(1);
        kafkaReturns(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.TRY_AGAIN);

        verify(values).increment("sale:1:stock");
        verify(sets).remove("sale:1:buyers", USER_ID);
    }

    @Test
    void sendRejectedBeforeLeavingProducerGivesTheUnitBack() {
        luaReturns(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("no metadata"));

        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.TRY_AGAIN);

        verify(values).increment("sale:1:stock");
        verify(sets).remove("sale:1:buyers", USER_ID);
    }

    @Test
    void timedOutSendKeepsUnitUntilKafkaFinallyFails() {
        luaReturns(1);
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        kafkaReturns(pending);

        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.TRY_AGAIN);
        verify(values, never()).increment(anyString());

        pending.completeExceptionally(new RuntimeException("delivery timeout"));

        verify(values).increment("sale:1:stock");
        verify(sets).remove("sale:1:buyers", USER_ID);
    }

    @Test
    void timedOutSendThatLaterSucceedsKeepsTheUnit() {
        luaReturns(1);
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        kafkaReturns(pending);

        assertThat(service.buy(SALE, USER).outcome()).isEqualTo(BuyOutcome.TRY_AGAIN);
        pending.complete(null);

        verify(values, never()).increment(anyString());
        verify(sets, never()).remove(anyString(), any());
    }

    @Test
    void countsEveryOutcome() {
        luaReturns(-1);
        service.buy(SALE, USER);
        service.buy(SALE, USER);
        luaReturns(0);
        service.buy(SALE, USER);
        service.buy(SALE, USER); // answered from the local sold-out flag, still counted

        assertThat(meters.get("flashsale.buy").tag("result", "ALREADY_BOUGHT").counter().count()).isEqualTo(2);
        assertThat(meters.get("flashsale.buy").tag("result", "SOLD_OUT").counter().count()).isEqualTo(2);
        assertThat(meters.get("flashsale.buy").tag("result", "SUCCESS").counter().count()).isZero();
    }
}
