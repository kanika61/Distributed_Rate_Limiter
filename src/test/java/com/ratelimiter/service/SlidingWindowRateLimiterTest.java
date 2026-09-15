package com.ratelimiter.service;

import com.ratelimiter.AbstractRedisIntegrationTest;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SlidingWindowRateLimiterTest extends AbstractRedisIntegrationTest {

    @Autowired
    private SlidingWindowRateLimiter slidingWindow;

    private String newClientId() {
        return "test-client-" + UUID.randomUUID();
    }

    private RateLimitRule rule(String clientId, int limit, int windowSeconds) {
        return new RateLimitRule(clientId, RateLimitAlgorithm.SLIDING_WINDOW, limit, 0, limit, windowSeconds);
    }

    @Test
    void allowsUpToLimitWithinWindow() {
        String clientId = newClientId();
        RateLimitRule rule = rule(clientId, 4, 10);

        for (int i = 0; i < 4; i++) {
            assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).as("request %d", i).isTrue();
        }
    }

    @Test
    void deniesRequestsBeyondLimitWithinWindow() {
        String clientId = newClientId();
        RateLimitRule rule = rule(clientId, 3, 10);

        for (int i = 0; i < 3; i++) {
            assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).isTrue();
        }

        RateLimitResult fourth = slidingWindow.tryConsume(clientId, rule);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    void allowsAgainOnceOldestEntryLeavesTheWindow() throws InterruptedException {
        String clientId = newClientId();
        // Very short window so the test doesn't need to sleep long.
        RateLimitRule rule = rule(clientId, 2, 1);

        assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).isTrue();
        assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).isTrue();
        assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).isFalse();

        Thread.sleep(1100);

        assertThat(slidingWindow.tryConsume(clientId, rule).allowed()).isTrue();
    }

    @Test
    void separateClientsHaveIndependentWindows() {
        String clientA = newClientId();
        String clientB = newClientId();
        RateLimitRule rule = rule("shared-rule", 1, 10);

        assertThat(slidingWindow.tryConsume(clientA, rule).allowed()).isTrue();
        assertThat(slidingWindow.tryConsume(clientA, rule).allowed()).isFalse();
        assertThat(slidingWindow.tryConsume(clientB, rule).allowed()).isTrue();
    }
}
