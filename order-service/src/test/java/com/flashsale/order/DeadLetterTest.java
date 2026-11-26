package com.flashsale.order;

import com.flashsale.order.config.KafkaConfig;
import com.flashsale.order.deadletter.DeadLetterService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end against a real Kafka 3.8 broker: a record the consumer cannot process lands on
 * order-created.DLT with its error, the partition keeps moving, and the record can be replayed
 * once the cause is fixed.
 */
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true"
})
class DeadLetterTest extends IntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    DeadLetterService deadLetters;

    @Autowired
    MeterRegistry meters;

    private static String event(UUID token, long saleId, UUID user) {
        return "{\"orderToken\":\"" + token + "\",\"saleId\":" + saleId + ",\"userId\":\"" + user + "\"}";
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(200);
        }
    }

    private boolean orderExists(UUID token) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM orders WHERE order_token = ?", Integer.class, token);
        return n != null && n == 1;
    }

    @Test
    void poisonRecordIsParkedAndTheNextRecordStillGoesThroughThenReplayRecovers() throws Exception {
        UUID user = UUID.randomUUID();
        String key = user.toString();
        UUID unknownSaleOrder = UUID.randomUUID();
        UUID goodOrder = UUID.randomUUID();
        double parkedBefore = meters.find("flashsale.orders.dead.lettered").counters().stream()
                .mapToDouble(c -> c.count()).sum();

        // Same key → same partition, in this order: garbage, an order for a sale that does not exist yet, a good order.
        kafka.send(KafkaConfig.ORDER_CREATED_TOPIC, key, "this is not json").get();
        kafka.send(KafkaConfig.ORDER_CREATED_TOPIC, key, event(unknownSaleOrder, 99, user)).get();
        kafka.send(KafkaConfig.ORDER_CREATED_TOPIC, key, event(goodOrder, 1, UUID.randomUUID())).get();

        // The good record behind the two bad ones is processed: the partition is not blocked.
        await("good order to be written", () -> orderExists(goodOrder));
        await("both bad records to be parked", () -> deadLetters.pending(10).size() == 2);

        List<DeadLetterService.DeadLetter> parked = deadLetters.pending(10);
        assertThat(parked).extracting(DeadLetterService.DeadLetter::payload)
                .containsExactly("this is not json", event(unknownSaleOrder, 99, user));
        assertThat(parked.get(0).error()).contains("InvalidEventException");
        assertThat(parked.get(1).error()).containsIgnoringCase("foreign key");
        assertThat(parked).allSatisfy(d -> {
            assertThat(d.originalTopic()).isEqualTo(KafkaConfig.ORDER_CREATED_TOPIC);
            assertThat(d.key()).isEqualTo(key);
        });
        // The shareable summary counts by exception class and carries no key, payload or error text.
        DeadLetterService.Summary summary = deadLetters.summary();
        assertThat(summary.pending()).isEqualTo(2);
        assertThat(summary.byCause().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(summary.byCause()).containsKey("InvalidEventException");
        assertThat(summary.oldest()).isNotNull().isBeforeOrEqualTo(summary.newest());
        assertThat(summary.toString()).doesNotContain(user.toString(), "not json", "foreign key", "sale_id");
        double parkedAfter = meters.find("flashsale.orders.dead.lettered").counters().stream()
                .mapToDouble(c -> c.count()).sum();
        assertThat(parkedAfter - parkedBefore).isEqualTo(2);

        // Fix the cause (the sale now exists), then replay: the order is created and nothing is pending.
        jdbc.update("""
                INSERT INTO flash_sales (id, product_id, total_stock, sale_price_paise, starts_at, ends_at)
                VALUES (99, 1, 10, 100, now(), now() + interval '1 day')
                """);
        assertThat(deadLetters.replayAll()).isEqualTo(2);
        await("replayed order to be written", () -> orderExists(unknownSaleOrder));

        // The garbage record cannot be fixed; replaying it sends it straight back, once.
        await("garbage to be parked again", () -> deadLetters.pending(10).size() == 1);
        assertThat(deadLetters.pending(10).get(0).payload()).isEqualTo("this is not json");
    }

    @Test
    void deadLetterReasonsAreABoundedSetRegisteredAtZero() {
        for (String reason : java.util.List.of("invalid_event", "integrity_violation", "undeserializable", "retries_exhausted")) {
            assertThat(meters.find("flashsale.orders.dead.lettered").tag("reason", reason).counter())
                    .as(reason).isNotNull();
        }
    }
}
