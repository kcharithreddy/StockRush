package com.flashsale.sale.web;

import com.flashsale.sale.redis.RedisKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Per-user fixed-window limiter on the buy endpoint (ARCHITECTURE.md §11):
 * INCR rl:{userId}:{epochSecond}, EXPIRE 2 s on the first hit, 429 above 5 per second.
 */
@Component
public class UserRateLimiter implements HandlerInterceptor {

    static final int MAX_PER_SECOND = 5;
    private static final Duration WINDOW_TTL = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public UserRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        OptionalLong userId = UserIds.from(request);
        if (userId.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        if (!allow(userId.getAsLong())) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return false;
        }
        return true;
    }

    boolean allow(long userId) {
        String key = RedisKeys.rateLimit(userId, clock.millis() / 1000);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, WINDOW_TTL);
        }
        return count != null && count <= MAX_PER_SECOND;
    }
}
