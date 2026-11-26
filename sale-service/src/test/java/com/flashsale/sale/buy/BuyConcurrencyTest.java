package com.flashsale.sale.buy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 200 buyers race for 10 units against a real Redis: exactly 10 must win. */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=false"})
class BuyConcurrencyTest {

    private static final long SALE = 1;
    private static final int STOCK = 10;
    private static final int BUYERS = 200;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        com.flashsale.sale.AclRedis.register(registry);
    }

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @Autowired
    BuyService buyService;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        com.flashsale.sale.AclRedis.flushAll();
        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:1:stock", Integer.toString(STOCK));
        long now = System.currentTimeMillis();
        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:1:window", (now - 60_000) + "," + (now + 3_600_000));
        buyService.clearSoldOutFlags();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void exactlyStockManyBuyersWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BuyOutcome>> results = new ArrayList<>();
        for (int user = 1; user <= BUYERS; user++) {
            UUID userId = UUID.randomUUID();
            results.add(pool.submit(() -> {
                start.await();
                return buyService.buy(SALE, userId).outcome();
            }));
        }
        start.countDown();

        int won = 0;
        int soldOut = 0;
        for (Future<BuyOutcome> result : results) {
            BuyOutcome outcome = result.get();
            if (outcome == BuyOutcome.SUCCESS) {
                won++;
            } else if (outcome == BuyOutcome.SOLD_OUT) {
                soldOut++;
            }
        }
        pool.shutdown();

        assertThat(won).isEqualTo(STOCK);
        assertThat(soldOut).isEqualTo(BUYERS - STOCK);
        assertThat(redis.opsForValue().get("sale:1:stock")).isEqualTo("0");
        assertThat(com.flashsale.sale.AclRedis.admin().opsForSet().size("sale:1:buyers")).isEqualTo(STOCK);
        verify(kafka, times(STOCK)).send(anyString(), anyString(), anyString());
    }
}
