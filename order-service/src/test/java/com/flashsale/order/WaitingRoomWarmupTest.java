package com.flashsale.order;

import com.flashsale.order.admin.SaleAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingRoomWarmupTest extends IntegrationTest {

    @Autowired
    SaleAdminService admin;

    @Test
    void warmupSwitchesTheWaitingRoomOnlyForSalesThatHaveOne() {
        admin.warmup(2); // seeded by V4 with waiting_room = true
        admin.warmup(1);

        assertThat(redis.opsForValue().get("sale:2:gate")).isEqualTo("1");
        assertThat(redis.opsForValue().get("sale:1:gate")).isNull();
        assertThat(AclRedis.admin().opsForSet().members("sale:gated")).containsExactly("2");
    }

    @Test
    void resetEmptiesTheWaitingRoom() {
        AclRedis.admin().opsForZSet().add("sale:2:queue", "someone", 1);
        AclRedis.admin().opsForSet().add("sale:2:admitted", "someone-else");

        admin.reset(2);

        assertThat(AclRedis.admin().hasKey("sale:2:queue")).isFalse();
        assertThat(AclRedis.admin().hasKey("sale:2:admitted")).isFalse();
        assertThat(redis.opsForValue().get("sale:2:gate")).isEqualTo("1");
    }
}
