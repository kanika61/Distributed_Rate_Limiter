package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sliding Window Log strategy. Each accepted request is a member of a Redis sorted set,
 * scored by its timestamp; {@code scripts/sliding_window.lua} evicts expired entries and
 * checks/records the new one atomically.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiterStrategy {

    private static final String KEY_PREFIX = "rl:sw:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> slidingWindowScript;

    public SlidingWindowRateLimiter(StringRedisTemplate redisTemplate,
                                     DefaultRedisScript<List> slidingWindowScript) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = slidingWindowScript;
    }

    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.SLIDING_WINDOW;
    }

    @Override
    public RateLimitResult tryConsume(String clientId, RateLimitRule rule) {
        String key = KEY_PREFIX + clientId;
        long now = System.currentTimeMillis();
        long windowMs = rule.getWindowSeconds() * 1000L;
        String member = now + "-" + UUID.randomUUID();

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                slidingWindowScript,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(rule.getLimit()),
                member
        );

        boolean allowed = result.get(0) == 1L;
        long remaining = result.get(1);
        long retryAfterMs = result.get(2);

        return allowed ? RateLimitResult.allow(remaining) : RateLimitResult.deny(retryAfterMs);
    }
}
