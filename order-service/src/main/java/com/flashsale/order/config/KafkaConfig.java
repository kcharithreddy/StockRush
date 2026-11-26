package com.flashsale.order.config;

import com.flashsale.order.order.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    public static final String ORDER_CREATED_TOPIC = "order-created";
    public static final String DEAD_LETTER_TOPIC = ORDER_CREATED_TOPIC + ".DLT";
    static final String DEAD_LETTER_METRIC = "flashsale.orders.dead.lettered";

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Same definition as sale-service, so either service can start first. */
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC).partitions(3).replicas(1).build();
    }

    /** Parking lot for records the consumer could not process. Same partition count: a record keeps its partition. */
    @Bean
    public NewTopic orderCreatedDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * Transient failures (database down, deadlock) are retried with exponential backoff: 0.5 s, 1 s, 2 s ...
     * capped at 30 s, for up to 5 minutes. Records that can never succeed (malformed payload, unknown sale)
     * are not retried. Either way, a record that is given up on is published to {@value #DEAD_LETTER_TOPIC}
     * with the error in its headers, counted, and logged; the partition moves on instead of blocking.
     * A buyer whose event lands there still holds a unit in Redis, so reconciliation reports redis_low
     * until the record is replayed (POST /admin/dead-letters/replay) or the unit is released by hand.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters) {
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));

        // A small, fixed set of reasons, each registered at 0 up front: Prometheus increase() cannot see the
        // first event of a series that is born at 1, and exception class names are not a bounded label.
        for (String reason : DEAD_LETTER_REASONS) {
            meters.counter(DEAD_LETTER_METRIC, "reason", reason);
        }

        ConsumerRecordRecoverer recoverer = (record, error) -> {
            Throwable cause = rootCause(error);
            String reason = reason(error);
            log.error("Dead-lettering {}-{}@{} (key {}) [{}]: {}: {}", record.topic(), record.partition(),
                    record.offset(), record.key(), reason, cause.getClass().getName(), cause.getMessage());
            toDeadLetterTopic.accept(record, error);
            meters.counter(DEAD_LETTER_METRIC, "reason", reason).increment();
        };

        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(300_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                InvalidEventException.class,
                DataIntegrityViolationException.class,
                DeserializationException.class);
        return handler;
    }

    static final java.util.List<String> DEAD_LETTER_REASONS =
            java.util.List.of("invalid_event", "integrity_violation", "undeserializable", "retries_exhausted");

    /** invalid_event / integrity_violation / undeserializable never succeed; anything else ran out of retries. */
    static String reason(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InvalidEventException) {
                return "invalid_event";
            }
            if (t instanceof DataIntegrityViolationException) {
                return "integrity_violation";
            }
            if (t instanceof DeserializationException) {
                return "undeserializable";
            }
        }
        return "retries_exhausted";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
