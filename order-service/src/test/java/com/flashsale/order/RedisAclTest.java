package com.flashsale.order;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** order-service's Redis user can manage sale keys and nothing else. */
class RedisAclTest extends IntegrationTest {

    @Test
    void canManageSaleKeys() {
        redis.opsForValue().set("sale:5:stock", "10");
        assertThat(redis.opsForValue().increment("sale:5:stock")).isEqualTo(11L);
        assertThat(redis.delete("sale:5:stock")).isTrue();
    }

    @Test
    void cannotRunScriptsTouchOtherKeysOrWipeRedis() {
        assertThatThrownBy(() -> redis.execute(RedisScript.of("return 1", Long.class), List.of("sale:5:stock")))
                .hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.opsForValue().get("rl:someone:1")).hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.opsForZSet().add("sale:5:queue", "x", 1)).hasStackTraceContaining("NOPERM");
        assertThatThrownBy(() -> redis.getConnectionFactory().getConnection().serverCommands().flushAll())
                .hasStackTraceContaining("NOPERM");
    }
}
