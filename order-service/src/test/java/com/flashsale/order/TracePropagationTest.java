package com.flashsale.order;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buyer's trace continues through Kafka: an order event that carries a W3C traceparent header is processed,
 * and its order written, inside that same trace, as a child of the producer's span. The spans hold no user id
 * or order token. Runs against the real Kafka broker of {@link DeadLetterTest}; spans are captured in memory.
 */
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "management.tracing.export.enabled=true",
        "management.tracing.export.otlp.enabled=false",
        "management.tracing.sampling.probability=1.0"
})
@Import(TracePropagationTest.CaptureSpans.class)
class TracePropagationTest extends IntegrationTest {

    @TestConfiguration
    static class CaptureSpans {
        @Bean
        InMemorySpanExporter inMemorySpans() {
            return InMemorySpanExporter.create();
        }
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", DeadLetterTest.KAFKA::getBootstrapServers);
    }

    @Autowired
    InMemorySpanExporter spans;

    @Test
    void orderProcessingAndTheDatabaseWriteJoinTheBuyersTrace() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String producerSpanId = "00f067aa0ba902b7";
        UUID user = UUID.randomUUID();
        UUID token = UUID.randomUUID();

        // A plain producer: the only trace context on the record is the header this test sets,
        // exactly as sale-service's KafkaTemplate sends it.
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, DeadLetterTest.KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()))) {
            ProducerRecord<String, String> record = new ProducerRecord<>("order-created", user.toString(),
                    "{\"orderToken\":\"" + token + "\",\"saleId\":1,\"userId\":\"" + user + "\"}");
            record.headers().add("traceparent",
                    ("00-" + traceId + "-" + producerSpanId + "-01").getBytes(StandardCharsets.UTF_8));
            producer.send(record).get();
        }

        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        List<SpanData> trace = List.of();
        while (Instant.now().isBefore(deadline)) {
            trace = spans.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
            if (trace.stream().anyMatch(s -> s.getName().equals("query"))
                    && trace.stream().anyMatch(s -> s.getName().endsWith(" process"))) {
                break;
            }
            Thread.sleep(250);
        }

        SpanData process = trace.stream().filter(s -> s.getName().equals("order-created process")).findFirst()
                .orElseThrow(() -> new AssertionError("no order-created process span in the buyer's trace"));
        assertThat(process.getParentSpanId()).as("child of the producer's span").isEqualTo(producerSpanId);

        SpanData insert = trace.stream().filter(s -> s.getName().equals("query")).findFirst()
                .orElseThrow(() -> new AssertionError("no database span in the buyer's trace"));
        assertThat(insert.getAttributes().asMap().toString()).contains("INSERT INTO orders");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_token = ?", Integer.class, token))
                .isEqualTo(1);

        // Spans are shipped to the tracing backend: they must not identify the buyer.
        assertThat(trace.toString()).doesNotContain(user.toString(), token.toString());
    }
}
