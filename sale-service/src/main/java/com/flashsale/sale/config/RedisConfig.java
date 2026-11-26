package com.flashsale.sale.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration
public class RedisConfig {

    public static final String ORDER_CREATED_TOPIC = "order-created";

    /** The atomic buy script; the only place stock is ever decremented. */
    @Bean
    public RedisScript<Long> buyScript() {
        return RedisScript.of(new ClassPathResource("scripts/buy.lua"), Long.class);
    }

    /** Atomic INCR + EXPIRE for the per-user rate limiter. */
    @Bean
    public RedisScript<Long> rateLimitScript() {
        return RedisScript.of(new ClassPathResource("scripts/rate_limit.lua"), Long.class);
    }

    /** Join the waiting room or read one's place in it. */
    @Bean
    public RedisScript<Long> queueScript() {
        return RedisScript.of(new ClassPathResource("scripts/queue.lua"), Long.class);
    }

    /** Lets the next batch in, at most once per second across all instances. */
    @Bean
    public RedisScript<Long> admitScript() {
        return RedisScript.of(new ClassPathResource("scripts/admit.lua"), Long.class);
    }

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
