package com.flashsale.sale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the Lua scripts against a real Redis 7. */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=false", "spring.data.redis.password="})
@Testcontainers
class RedisScriptsTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.12-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    KafkaTemplate<String, String> kafka;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    @Qualifier("buyScript")
    RedisScript<Long> buyScript;

    @Autowired
    @Qualifier("rateLimitScript")
    RedisScript<Long> rateLimitScript;

    private static final List<String> SALE_KEYS = List.of("sale:9:stock", "sale:9:buyers", "sale:9:window");

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void rateLimitCountsAtomicallyAndAlwaysSetsTtl() {
        for (int i = 1; i <= 7; i++) {
            assertThat(redis.execute(rateLimitScript, List.of("rl:1:100"), "2")).isEqualTo(i);
        }
        Long ttl = redis.getExpire("rl:1:100");
        assertThat(ttl).isBetween(1L, 2L);
    }

    @Test
    void buyUsesRedisClockForTheWindow() {
        long now = System.currentTimeMillis();
        redis.opsForValue().set("sale:9:stock", "5");

        redis.opsForValue().set("sale:9:window", (now + 600_000) + "," + (now + 1_200_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);

        redis.opsForValue().set("sale:9:window", (now - 1_200_000) + "," + (now - 600_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);

        redis.opsForValue().set("sale:9:window", (now - 600_000) + "," + (now + 600_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(1L);
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-1L);
        assertThat(redis.opsForValue().get("sale:9:stock")).isEqualTo("4");
    }

    @Test
    void buyWithoutWindowIsNotActive() {
        redis.opsForValue().set("sale:9:stock", "5");
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);
    }
}
