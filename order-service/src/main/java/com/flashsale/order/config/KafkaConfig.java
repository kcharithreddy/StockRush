package com.flashsale.order.config;

import com.flashsale.order.order.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    public static final String ORDER_CREATED_TOPIC = "order-created";

    /** Same definition as sale-service, so either service can start first. */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * Transient failures (database down, deadlock) are retried with exponential backoff:
     * 0.5 s, 1 s, 2 s ... capped at 30 s, for up to 5 minutes. Records that can never succeed
     * (malformed payload, unknown sale) are logged and skipped at once instead of blocking the
     * partition. A dead-letter topic replaces the skip in Step C (ARCHITECTURE.md §17).
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(300_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(
                InvalidEventException.class,
                DataIntegrityViolationException.class,
                DeserializationException.class);
        return handler;
    }
}
