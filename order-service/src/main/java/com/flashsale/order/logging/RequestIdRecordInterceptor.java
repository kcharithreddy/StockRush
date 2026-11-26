package com.flashsale.order.logging;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Puts the request_id that sale-service attached to an order-created record into the MDC while the record is
 * processed, so the consumer's log lines (and a dead-letter, which keeps the original headers) can be matched
 * to the buy request that produced them. Spring Boot applies this bean to the listener container factory — but
 * only a {@code RecordInterceptor<Object, Object>}: a {@code <String, String>} bean is silently ignored.
 */
@Component
public class RequestIdRecordInterceptor implements RecordInterceptor<Object, Object> {

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        Header header = record.headers().lastHeader(RequestIds.KAFKA_HEADER);
        String incoming = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        MDC.put(RequestIds.MDC_KEY, RequestIds.acceptOrGenerate(incoming));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        MDC.remove(RequestIds.MDC_KEY);
    }
}
