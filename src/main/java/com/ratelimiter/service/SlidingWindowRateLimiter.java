package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
    private final byte[] scriptBytes;

    public SlidingWindowRateLimiter(StringRedisTemplate redisTemplate,
                                     DefaultRedisScript<List> slidingWindowScript) {
        this.redisTemplate = redisTemplate;
        // Send the script body with plain EVAL on every call instead of going through
        // RedisTemplate's default EVALSHA-then-fallback-to-EVAL path: EVALSHA against a
        // script Redis hasn't cached yet should fail fast with NOSCRIPT, but on some
        // CI/Docker networking setups that call has been observed to hang for the full
        // client timeout instead of returning quickly, which then poisons the shared
        // connection for every subsequent command. The script is small, so resending its
        // full text each call costs nothing meaningful for a rate limiter's traffic.
        this.scriptBytes = slidingWindowScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
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

        RedisSerializer<String> serializer = RedisSerializer.string();
        byte[][] keysAndArgs = {
                serializer.serialize(key),
                serializer.serialize(String.valueOf(now)),
                serializer.serialize(String.valueOf(windowMs)),
                serializer.serialize(String.valueOf(rule.getLimit())),
                serializer.serialize(member)
        };

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Long>>) connection ->
                (List<Long>) (List<?>) connection.scriptingCommands()
                        .eval(scriptBytes, ReturnType.MULTI, 1, keysAndArgs));

        boolean allowed = result.get(0) == 1L;
        long remaining = result.get(1);
        long retryAfterMs = result.get(2);

        return allowed ? RateLimitResult.allow(remaining) : RateLimitResult.deny(retryAfterMs);
    }
}
