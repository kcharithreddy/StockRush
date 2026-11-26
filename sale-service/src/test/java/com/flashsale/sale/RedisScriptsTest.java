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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the Lua scripts against a real Redis 7. */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=false"})
class RedisScriptsTest {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        com.flashsale.sale.AclRedis.register(registry);
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
        com.flashsale.sale.AclRedis.flushAll();
    }

    @Test
    void rateLimitCountsAtomicallyAndAlwaysSetsTtl() {
        for (int i = 1; i <= 7; i++) {
            assertThat(redis.execute(rateLimitScript, List.of("rl:1:100"), "2")).isEqualTo(i);
        }
        Long ttl = com.flashsale.sale.AclRedis.admin().getExpire("rl:1:100");
        assertThat(ttl).isBetween(1L, 2L);
    }

    @Test
    void buyUsesRedisClockForTheWindow() {
        long now = System.currentTimeMillis();
        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:9:stock", "5");

        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:9:window", (now + 600_000) + "," + (now + 1_200_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);

        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:9:window", (now - 1_200_000) + "," + (now - 600_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);

        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:9:window", (now - 600_000) + "," + (now + 600_000));
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(1L);
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-1L);
        assertThat(redis.opsForValue().get("sale:9:stock")).isEqualTo("4");
    }

    @Test
    void buyWithoutWindowIsNotActive() {
        com.flashsale.sale.AclRedis.admin().opsForValue().set("sale:9:stock", "5");
        assertThat(redis.execute(buyScript, SALE_KEYS, "1")).isEqualTo(-2L);
    }

    @Test
    void serviceUserIsConfinedByTheAcl() {
        // Allowed: exactly what the buy path needs.
        assertThat(redis.opsForValue().get("sale:9:stock")).isNull();
        assertThat(redis.opsForValue().increment("sale:9:stock")).isEqualTo(1L);

        // Denied: writing a sale's configuration, touching other keys, wiping or reconfiguring Redis.
        assertThatThrownBy(() -> redis.opsForValue().set("sale:9:stock", "1000000")).hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.opsForValue().get("session:abc")).hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.getConnectionFactory().getConnection().serverCommands().flushAll())
                .hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.getConnectionFactory().getConnection().serverCommands().getConfig("*"))
                .hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.keys("*")).hasStackTraceContaining("NOPERM");
    }
}
