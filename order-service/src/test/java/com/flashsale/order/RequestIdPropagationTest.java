package com.flashsale.order;

import com.flashsale.order.logging.RequestIdRecordInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards against the interceptor silently not being applied (Boot only wires RecordInterceptor<Object, Object>). */
class RequestIdPropagationTest extends IntegrationTest {

    @Autowired
    ConcurrentKafkaListenerContainerFactory<?, ?> factory;

    @Test
    void listenerFactoryUsesTheRequestIdInterceptor() {
        assertThat(ReflectionTestUtils.getField(factory, "recordInterceptor"))
                .isInstanceOf(RequestIdRecordInterceptor.class);
    }
}
