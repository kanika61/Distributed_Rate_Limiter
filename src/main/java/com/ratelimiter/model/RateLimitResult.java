package com.ratelimiter.model;

/**
 * Outcome of a single {@code tryConsume} call against a rate limiting strategy.
 *
 * @param allowed        whether the request may proceed
 * @param remaining      tokens (token bucket) or free slots (sliding window) left after this call
 * @param retryAfterMs   how long the caller should wait before retrying, in milliseconds
 *                       (only meaningful when {@code allowed} is false)
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMs) {

    public static RateLimitResult allow(long remaining) {
        return new RateLimitResult(true, remaining, 0);
    }

    public static RateLimitResult deny(long retryAfterMs) {
        return new RateLimitResult(false, 0, retryAfterMs);
    }
}
