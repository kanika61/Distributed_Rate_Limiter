package com.ratelimiter.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A per-client (or default/global) rate limit configuration.
 *
 * <p>Fields are shared across both algorithms but interpreted differently:
 * <ul>
 *   <li>{@link RateLimitAlgorithm#TOKEN_BUCKET} uses {@code capacity} (max burst size) and
 *       {@code refillRatePerSecond} (steady-state throughput).</li>
 *   <li>{@link RateLimitAlgorithm#SLIDING_WINDOW} uses {@code limit} (max requests) and
 *       {@code windowSeconds} (the trailing window size).</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitRule {

    // Not @NotNull: the admin API's PUT endpoints accept a rule body without a clientId
    // (it comes from the path variable) and fill it in server-side before persisting --
    // see AdminController.
    private String clientId;

    @NotNull
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;

    @Min(1)
    private int capacity = 20;

    @Min(0)
    private double refillRatePerSecond = 5.0;

    @Min(1)
    private int limit = 20;

    @Min(1)
    private int windowSeconds = 10;

    public RateLimitRule() {
    }

    public RateLimitRule(String clientId, RateLimitAlgorithm algorithm, int capacity,
                          double refillRatePerSecond, int limit, int windowSeconds) {
        this.clientId = clientId;
        this.algorithm = algorithm;
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public static RateLimitRule defaultTokenBucket(String clientId, int capacity, double refillRatePerSecond) {
        return new RateLimitRule(clientId, RateLimitAlgorithm.TOKEN_BUCKET, capacity, refillRatePerSecond,
                capacity, 1);
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(RateLimitAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    public void setRefillRatePerSecond(double refillRatePerSecond) {
        this.refillRatePerSecond = refillRatePerSecond;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    @Override
    public String toString() {
        return "RateLimitRule{" +
                "clientId='" + clientId + '\'' +
                ", algorithm=" + algorithm +
                ", capacity=" + capacity +
                ", refillRatePerSecond=" + refillRatePerSecond +
                ", limit=" + limit +
                ", windowSeconds=" + windowSeconds +
                '}';
    }
}
