package com.flashsale.order.deadletter;

import com.flashsale.order.config.KafkaConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads and replays the dead-letter topic.
 *
 * <p>A dedicated consumer group ({@value #REPLAY_GROUP}) remembers how far replay has got, so each record is
 * replayed once. Listing never moves that position. Replaying re-publishes the original payload and key to
 * order-created; the consumer is idempotent, so a record that was in fact already processed changes nothing,
 * and one that still fails simply comes back to the dead-letter topic.
 */
@Service
public class DeadLetterService {

    static final String REPLAY_GROUP = "order-service-dlt-replay";
    private static final Duration POLL = Duration.ofMillis(500);
    private static final int MAX_EMPTY_POLLS = 6;
    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    public record DeadLetter(int partition, long offset, String key, String payload,
                             String error, String originalTopic, Long originalOffset) {
    }

    /**
     * Pending records counted by the exception class that sent them to the DLT. Holds no keys, payloads or
     * exception messages (those can quote user ids or event content), so it is safe to share outside the system.
     */
    public record Summary(int pending, Map<String, Integer> byCause, Instant oldest, Instant newest) {
    }

    private final ConsumerFactory<String, String> consumers;
    private final KafkaTemplate<String, String> producer;

    public DeadLetterService(ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> producer) {
        this.consumers = consumers;
        this.producer = producer;
    }

    /** Records not yet replayed, oldest first per partition (at most {@code limit}). */
    public List<DeadLetter> pending(int limit) {
        List<DeadLetter> result = new ArrayList<>();
        try (Consumer<String, String> consumer = open()) {
            readPending(consumer, record -> {
                if (result.size() < limit) {
                    result.add(describe(record));
                }
            });
        }
        return result;
    }

    /** Counts of the records not yet replayed. See {@link Summary} for what it leaves out. */
    public Summary summary() {
        Map<String, Integer> byCause = new TreeMap<>();
        long[] range = {Long.MAX_VALUE, Long.MIN_VALUE};
        int[] count = {0};
        try (Consumer<String, String> consumer = open()) {
            readPending(consumer, record -> {
                count[0]++;
                byCause.merge(simpleName(header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)), 1, Integer::sum);
                range[0] = Math.min(range[0], record.timestamp());
                range[1] = Math.max(range[1], record.timestamp());
            });
        }
        return count[0] == 0
                ? new Summary(0, byCause, null, null)
                : new Summary(count[0], byCause, Instant.ofEpochMilli(range[0]), Instant.ofEpochMilli(range[1]));
    }

    private static String simpleName(String className) {
        if (className == null || className.isBlank()) {
            return "unknown";
        }
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Re-publishes every pending record to order-created and marks them replayed. Returns how many. */
    public synchronized int replayAll() {
        int replayed = 0;
        try (Consumer<String, String> consumer = open()) {
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            readPending(consumer, records::add);
            Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
            for (ConsumerRecord<String, String> record : records) {
                try {
                    producer.send(KafkaConfig.ORDER_CREATED_TOPIC, record.key(), record.value()).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Stop at the first failure: everything before it is committed, the rest stays pending.
                    log.error("Replay stopped at {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
                    break;
                }
                done.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
                replayed++;
            }
            if (!done.isEmpty()) {
                consumer.commitSync(done);
            }
        }
        log.warn("Replayed {} dead-lettered order event(s) to {}", replayed, KafkaConfig.ORDER_CREATED_TOPIC);
        return replayed;
    }

    private Consumer<String, String> open() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = consumers.createConsumer(REPLAY_GROUP, "", null, overrides);
        List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.DEAD_LETTER_TOPIC).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .toList();
        consumer.assign(partitions);
        return consumer;
    }

    /** Reads from the replay group's committed position up to the current end of every partition. */
    private static void readPending(Consumer<String, String> consumer,
                                    java.util.function.Consumer<ConsumerRecord<String, String>> sink) {
        Map<TopicPartition, Long> end = consumer.endOffsets(consumer.assignment());
        int emptyPolls = 0;
        while (!caughtUp(consumer, end) && emptyPolls < MAX_EMPTY_POLLS) {
            var batch = consumer.poll(POLL);
            if (batch.isEmpty()) {
                emptyPolls++;
            }
            for (ConsumerRecord<String, String> record : batch) {
                if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                    sink.accept(record);
                }
            }
        }
    }

    private static boolean caughtUp(Consumer<String, String> consumer, Map<TopicPartition, Long> end) {
        return end.entrySet().stream().allMatch(e -> consumer.position(e.getKey()) >= e.getValue());
    }

    private static DeadLetter describe(ConsumerRecord<String, String> record) {
        String offset = header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        return new DeadLetter(record.partition(), record.offset(), record.key(), record.value(),
                header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN) + ": "
                        + header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                offset == null ? null : Long.valueOf(offset));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            return null;
        }
        byte[] value = header.value();
        if (KafkaHeaders.DLT_ORIGINAL_OFFSET.equals(name) && value.length == Long.BYTES) {
            return String.valueOf(java.nio.ByteBuffer.wrap(value).getLong());
        }
        return new String(value, StandardCharsets.UTF_8);
    }
}
