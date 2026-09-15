package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.model.RateLimitRule;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Facade used by the filter: resolves the effective rule for a client, picks the
 * configured {@link RateLimiterStrategy} (Strategy pattern), and delegates the
 * check-and-decrement call to it.
 */
@Service
public class RateLimiterService {

    private final RuleService ruleService;
    private final Map<RateLimitAlgorithm, RateLimiterStrategy> strategies;

    public RateLimiterService(RuleService ruleService, List<RateLimiterStrategy> strategyBeans) {
        this.ruleService = ruleService;
        this.strategies = new EnumMap<>(RateLimitAlgorithm.class);
        for (RateLimiterStrategy strategy : strategyBeans) {
            strategies.put(strategy.getAlgorithm(), strategy);
        }
    }

    public RateLimitResult tryConsume(String clientId) {
        RateLimitRule rule = ruleService.resolveRule(clientId);
        RateLimiterStrategy strategy = strategies.get(rule.getAlgorithm());
        if (strategy == null) {
            throw new IllegalStateException("No strategy registered for algorithm: " + rule.getAlgorithm());
        }
        return strategy.tryConsume(clientId, rule);
    }
}
