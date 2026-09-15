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

/**
 * Token Bucket strategy. State for each client lives in a single Redis hash key,
 * mutated atomically by {@code scripts/token_bucket.lua} via EVAL.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiterStrategy {

    private static final String KEY_PREFIX = "rl:tb:";
    private static final long REQUESTED_TOKENS = 1L;

    private final StringRedisTemplate redisTemplate;
    private final byte[] scriptBytes;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                   DefaultRedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        // Plain EVAL every call rather than RedisTemplate's default EVALSHA-then-fallback:
        // see SlidingWindowRateLimiter for why -- EVALSHA against an uncached script has
        // been observed to hang (instead of failing fast with NOSCRIPT) on some CI/Docker
        // networking setups, poisoning the shared connection for later commands.
        this.scriptBytes = tokenBucketScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitResult tryConsume(String clientId, RateLimitRule rule) {
        String key = KEY_PREFIX + clientId;
        long now = System.currentTimeMillis();

        RedisSerializer<String> serializer = RedisSerializer.string();
        byte[][] keysAndArgs = {
                serializer.serialize(key),
                serializer.serialize(String.valueOf(rule.getCapacity())),
                serializer.serialize(String.valueOf(rule.getRefillRatePerSecond())),
                serializer.serialize(String.valueOf(now)),
                serializer.serialize(String.valueOf(REQUESTED_TOKENS))
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
