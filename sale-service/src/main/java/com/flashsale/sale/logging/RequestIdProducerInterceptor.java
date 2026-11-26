package com.flashsale.sale.logging;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Copies the current request_id onto every record sent to Kafka. Kafka calls onSend on the sending thread,
 * so the MDC of the HTTP request that triggered the send is still in place.
 * Registered through spring.kafka.producer.properties.interceptor.classes.
 */
public class RequestIdProducerInterceptor implements ProducerInterceptor<String, String> {

    @Override
    public ProducerRecord<String, String> onSend(ProducerRecord<String, String> record) {
        String requestId = MDC.get(RequestIds.MDC_KEY);
        if (requestId != null && record.headers().lastHeader(RequestIds.KAFKA_HEADER) == null) {
            record.headers().add(RequestIds.KAFKA_HEADER, requestId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
}
