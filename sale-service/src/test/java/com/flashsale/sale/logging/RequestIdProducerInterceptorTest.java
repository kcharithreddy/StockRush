package com.flashsale.sale.logging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdProducerInterceptorTest {

    private final RequestIdProducerInterceptor interceptor = new RequestIdProducerInterceptor();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void copiesTheRequestIdOntoTheRecord() {
        MDC.put(RequestIds.MDC_KEY, "req-12345678");
        ProducerRecord<String, String> record = interceptor.onSend(new ProducerRecord<>("order-created", "k", "v"));
        assertThat(new String(record.headers().lastHeader(RequestIds.KAFKA_HEADER).value(), StandardCharsets.UTF_8))
                .isEqualTo("req-12345678");
    }

    @Test
    void leavesRecordAloneOutsideARequest() {
        ProducerRecord<String, String> record = interceptor.onSend(new ProducerRecord<>("order-created", "k", "v"));
        assertThat(record.headers().lastHeader(RequestIds.KAFKA_HEADER)).isNull();
    }
}
