package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;

/**
 * Strategy interface (Strategy pattern) for a single check-and-decrement rate limit
 * operation. Implementations must be safe to call concurrently from multiple JVMs against
 * the same shared Redis backend.
 */
public interface RateLimiterStrategy {

    RateLimitAlgorithm getAlgorithm();

    RateLimitResult tryConsume(String clientId, RateLimitRule rule);
}
