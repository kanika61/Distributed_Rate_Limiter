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
        RateLimitRule rule = RateLimitRule.defaultTokenBucket(clientId, 3, 100.0);

        assertThat(tokenBucket.tryConsume(clientId, rule).allowed()).isTrue();
        Thread.sleep(200); // plenty of time to "overflow" past capacity if refill wasn't capped

        int allowedCount = 0;
        for (int i = 0; i < 10; i++) {
            if (tokenBucket.tryConsume(clientId, rule).allowed()) {
                allowedCount++;
            }
        }
        // Capacity is 3 total; one was already consumed above, so at most 2 more fit,
        // regardless of how much idle time passed.
        assertThat(allowedCount).isLessThanOrEqualTo(2);
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
