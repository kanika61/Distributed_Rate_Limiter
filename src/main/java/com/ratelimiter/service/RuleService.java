package com.ratelimiter.service;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.repository.RateLimitRuleRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Manages per-client {@link RateLimitRule}s and the fallback rule applied to clients that
 * have no explicit rule configured. Rules live in Redis so every app instance and the
 * admin API observe the same configuration.
 */
@Service
public class RuleService {

    /** Sentinel "client id" the default/global rule is stored under. */
    public static final String DEFAULT_CLIENT_KEY = "__default__";

    private final RateLimitRuleRepository repository;
    private final RateLimiterProperties properties;

    public RuleService(RateLimitRuleRepository repository, RateLimiterProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void seedDefaultRuleIfMissing() {
        if (repository.findByClientId(DEFAULT_CLIENT_KEY).isEmpty()) {
            repository.save(buildDefaultRuleFromProperties());
        }
    }

    /** Resolves the effective rule for a client: its own rule, or the shared default. */
    public RateLimitRule resolveRule(String clientId) {
        return repository.findByClientId(clientId)
                .orElseGet(this::getDefaultRule);
    }

    public RateLimitRule getDefaultRule() {
        return repository.findByClientId(DEFAULT_CLIENT_KEY)
                .orElseGet(this::buildDefaultRuleFromProperties);
    }

    public void setDefaultRule(RateLimitRule rule) {
        rule.setClientId(DEFAULT_CLIENT_KEY);
        repository.save(rule);
    }

    public RateLimitRule getRule(String clientId) {
        return repository.findByClientId(clientId)
                .orElseThrow(() -> new java.util.NoSuchElementException("No rule configured for client: " + clientId));
    }

    public void upsertRule(RateLimitRule rule) {
        repository.save(rule);
    }

    public void deleteRule(String clientId) {
        repository.delete(clientId);
    }

    /** All explicit per-client rules, excluding the default/global sentinel entry. */
    public Map<String, RateLimitRule> getAllClientRules() {
        Map<String, RateLimitRule> all = repository.findAll();
        all.remove(DEFAULT_CLIENT_KEY);
        return all;
    }

    private RateLimitRule buildDefaultRuleFromProperties() {
        RateLimiterProperties.DefaultRule defaults = properties.getDefaultRule();
        return new RateLimitRule(
                DEFAULT_CLIENT_KEY,
                defaults.getAlgorithm(),
                defaults.getCapacity(),
                defaults.getRefillRatePerSecond(),
                defaults.getLimit(),
                defaults.getWindowSeconds()
        );
    }
}
