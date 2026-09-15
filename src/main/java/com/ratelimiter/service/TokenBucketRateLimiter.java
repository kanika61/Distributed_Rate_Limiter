package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token Bucket strategy. State for each client lives in a single Redis hash key,
 * mutated atomically by {@code scripts/token_bucket.lua} via EVALSHA/EVAL.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiterStrategy {

    private static final String KEY_PREFIX = "rl:tb:";
    private static final long REQUESTED_TOKENS = 1L;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                   DefaultRedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    @Override
    public RateLimitAlgorithm getAlgorithm() {
        return RateLimitAlgorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitResult tryConsume(String clientId, RateLimitRule rule) {
        String key = KEY_PREFIX + clientId;
        long now = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                List.of(key),
                String.valueOf(rule.getCapacity()),
                String.valueOf(rule.getRefillRatePerSecond()),
                String.valueOf(now),
                String.valueOf(REQUESTED_TOKENS)
        );

        boolean allowed = result.get(0) == 1L;
        long remaining = result.get(1);
        long retryAfterMs = result.get(2);

        return allowed ? RateLimitResult.allow(remaining) : RateLimitResult.deny(retryAfterMs);
    }
}
