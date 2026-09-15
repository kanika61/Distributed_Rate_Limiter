package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring context, no Redis) for the strategy-dispatch logic: given a
 * resolved rule's algorithm, the correct {@link RateLimiterStrategy} bean must be invoked.
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Mock
    private RuleService ruleService;

    @Mock
    private RateLimiterStrategy tokenBucketStrategy;

    @Mock
    private RateLimiterStrategy slidingWindowStrategy;

    @Test
    void dispatchesToTokenBucketStrategyWhenRuleSaysSo() {
        when(tokenBucketStrategy.getAlgorithm()).thenReturn(RateLimitAlgorithm.TOKEN_BUCKET);
        when(slidingWindowStrategy.getAlgorithm()).thenReturn(RateLimitAlgorithm.SLIDING_WINDOW);

        RateLimiterService service = new RateLimiterService(ruleService, List.of(tokenBucketStrategy, slidingWindowStrategy));

        RateLimitRule rule = RateLimitRule.defaultTokenBucket("client-1", 10, 2.0);
        when(ruleService.resolveRule("client-1")).thenReturn(rule);
        when(tokenBucketStrategy.tryConsume("client-1", rule)).thenReturn(RateLimitResult.allow(9));

        RateLimitResult result = service.tryConsume("client-1");

        assertThat(result.allowed()).isTrue();
        verify(tokenBucketStrategy).tryConsume("client-1", rule);
        verify(slidingWindowStrategy, never()).tryConsume(anyString(), any());
    }

    @Test
    void dispatchesToSlidingWindowStrategyWhenRuleSaysSo() {
        when(tokenBucketStrategy.getAlgorithm()).thenReturn(RateLimitAlgorithm.TOKEN_BUCKET);
        when(slidingWindowStrategy.getAlgorithm()).thenReturn(RateLimitAlgorithm.SLIDING_WINDOW);

        RateLimiterService service = new RateLimiterService(ruleService, List.of(tokenBucketStrategy, slidingWindowStrategy));

        RateLimitRule rule = new RateLimitRule("client-2", RateLimitAlgorithm.SLIDING_WINDOW, 10, 0, 10, 5);
        when(ruleService.resolveRule("client-2")).thenReturn(rule);
        when(slidingWindowStrategy.tryConsume("client-2", rule)).thenReturn(RateLimitResult.deny(500));

        RateLimitResult result = service.tryConsume("client-2");

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterMs()).isEqualTo(500);
        verify(slidingWindowStrategy).tryConsume("client-2", rule);
        verify(tokenBucketStrategy, never()).tryConsume(anyString(), any());
    }
}
