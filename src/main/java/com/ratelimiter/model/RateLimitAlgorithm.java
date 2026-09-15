package com.ratelimiter.model;

/**
 * The swappable rate-limiting strategies backing {@link RateLimitRule}.
 */
public enum RateLimitAlgorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW
}
