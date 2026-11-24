package com.flashsale.sale.web;

import com.flashsale.sale.redis.RedisKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Clock;
import java.util.List;
import java.util.OptionalLong;

/**
 * Per-user fixed-window limiter on the buy endpoint (ARCHITECTURE.md §11):
 * rl:{userId}:{epochSecond} counted and expired atomically in rate_limit.lua, 429 above 5 per second.
 */
@Component
public class UserRateLimiter implements HandlerInterceptor {

    static final int MAX_PER_SECOND = 5;
    private static final String WINDOW_TTL_SECONDS = "2";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> rateLimitScript;
    private final Clock clock;

    public UserRateLimiter(StringRedisTemplate redis,
                           @Qualifier("rateLimitScript") RedisScript<Long> rateLimitScript,
                           Clock clock) {
        this.redis = redis;
        this.rateLimitScript = rateLimitScript;
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
            response.setHeader("Retry-After", "1");
            return false;
        }
        return true;
    }

    boolean allow(long userId) {
        String key = RedisKeys.rateLimit(userId, clock.millis() / 1000);
        Long count = redis.execute(rateLimitScript, List.of(key), WINDOW_TTL_SECONDS);
        return count != null && count <= MAX_PER_SECOND;
    }
}
