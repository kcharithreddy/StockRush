package com.flashsale.sale.queue;

import com.flashsale.sale.redis.RedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/** Joining the waiting room and reading one's place in it (ARCHITECTURE.md §18). */
@Service
public class WaitingRoomService {

    private static final double LOTTERY_RANGE = 1e9;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> queueScript;
    private final SecureRandom random = new SecureRandom();

    public WaitingRoomService(StringRedisTemplate redis, @Qualifier("queueScript") RedisScript<Long> queueScript) {
        this.redis = redis;
        this.queueScript = queueScript;
    }

    public QueueStatus join(long saleId, UUID userId) {
        return run(saleId, userId, "join");
    }

    public QueueStatus status(long saleId, UUID userId) {
        return run(saleId, userId, "status");
    }

    private QueueStatus run(long saleId, UUID userId, String mode) {
        Long result = redis.execute(queueScript,
                List.of(RedisKeys.gate(saleId), RedisKeys.queue(saleId), RedisKeys.admitted(saleId),
                        RedisKeys.window(saleId), RedisKeys.stock(saleId)),
                userId.toString(), mode, Double.toString(random.nextDouble() * LOTTERY_RANGE));
        if (result == null) {
            throw new IllegalStateException("queue.lua returned no result");
        }
        if (result > 0) {
            return new QueueStatus("WAITING", result);
        }
        return QueueStatus.of(switch (result.intValue()) {
            case 0 -> "ADMITTED";
            case -1 -> "NO_WAITING_ROOM";
            case -2 -> "SALE_NOT_ACTIVE";
            case -4 -> "SOLD_OUT";
            case -5 -> "NOT_IN_QUEUE";
            default -> throw new IllegalStateException("Unexpected queue.lua result: " + result);
        });
    }
}
