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

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
