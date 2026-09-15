package com.ratelimiter.service;

import com.ratelimiter.AbstractRedisIntegrationTest;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TokenBucketRateLimiterTest extends AbstractRedisIntegrationTest {

    @Autowired
    private TokenBucketRateLimiter tokenBucket;

    private String newClientId() {
        return "test-client-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToBurstCapacity() {
        String clientId = newClientId();
        RateLimitRule rule = RateLimitRule.defaultTokenBucket(clientId, 5, 1.0);

        for (int i = 0; i < 5; i++) {
            RateLimitResult result = tokenBucket.tryConsume(clientId, rule);
            assertThat(result.allowed()).as("request %d should be allowed", i).isTrue();
        }
    }

    @Test
    void deniesRequestsBeyondBurstCapacity() {
        String clientId = newClientId();
        RateLimitRule rule = RateLimitRule.defaultTokenBucket(clientId, 3, 1.0);

        for (int i = 0; i < 3; i++) {
            assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isTrue();
        }

        RateLimitResult fourth = tokenBucket.tryConsume(clientId, rule);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        String clientId = newClientId();
        // capacity 2, refill 10 tokens/sec -> ~100ms per token
        RateLimitRule rule = RateLimitRule.defaultTokenBucket(clientId, 2, 10.0);

        assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isTrue();
        assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isTrue();
        assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isFalse();

        Thread.sleep(250); // should refill ~2-3 tokens, capped at capacity

        assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isTrue();
    }

    @Test
    void neverExceedsCapacityEvenAfterLongIdlePeriod() throws InterruptedException {
        String clientId = newClientId();
        int capacity = 3;
        RateLimitRule rule = RateLimitRule.defaultTokenBucket(clientId, capacity, 100.0);

        // Touch the bucket once so it has a real "last refill" timestamp to compute from.
        tokenBucket.tryConsume(clientId, rule);

        // Nominal refill over this idle period (100 tokens/sec * 0.3s = 30) massively
        // exceeds capacity; the bucket must cap at `capacity`, not accumulate 30 tokens.
        Thread.sleep(300);

        // The very next call's "remaining" tells us the bucket's post-refill level
        // directly, without depending on how fast subsequent round trips happen to be.
        RateLimitResult result = tokenBucket.tryConsume(clientId, rule);
        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(capacity - 1); // capped at capacity, not 31
    }

    @Test
    void separateClientsHaveIndependentBuckets() {
        String clientA = newClientId();
        String clientB = newClientId();
        RateLimitRule rule = RateLimitRule.defaultTokenBucket("shared-rule", 1, 1.0);

        assertThat(tokenBucket.tryConsume(clientA, rule).allowed()).isTrue();
        assertThat(tokenBucket.tryConsume(clientA, rule).allowed()).isFalse();
        // client B's bucket is untouched by client A's consumption
        assertThat(tokenBucket.tryConsume(clientB, rule).allowed()).isTrue();
    }
}
