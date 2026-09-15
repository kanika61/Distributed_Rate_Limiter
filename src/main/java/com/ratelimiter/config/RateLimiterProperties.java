package com.ratelimiter.config;

import com.ratelimiter.model.RateLimitAlgorithm;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    /** HTTP header used to identify the calling client. */
    private String clientHeader = "X-API-Key";

    /** Ant-style path patterns that bypass the rate limit filter entirely. */
    private List<String> excludedPaths = List.of("/actuator/**", "/admin/**");

    private DefaultRule defaultRule = new DefaultRule();

    public String getClientHeader() {
        return clientHeader;
    }

    public void setClientHeader(String clientHeader) {
        this.clientHeader = clientHeader;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }

    public DefaultRule getDefaultRule() {
        return defaultRule;
    }

    public void setDefaultRule(DefaultRule defaultRule) {
        this.defaultRule = defaultRule;
    }

    /** Fallback rule applied to any client id that has no explicit rule configured. */
    public static class DefaultRule {
        private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
        private int capacity = 20;
        private double refillRatePerSecond = 5.0;
        private int limit = 20;
        private int windowSeconds = 10;

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
    }
}
