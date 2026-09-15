package com.ratelimiter.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.model.RateLimitRule;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Persists per-client {@link RateLimitRule}s in a single Redis hash so every app instance
 * (and the admin API) reads/writes the same source of truth. Rules are stored as JSON
 * values, keyed by client id, under the hash key {@code rl:rules}.
 */
@Repository
public class RateLimitRuleRepository {

    private static final String RULES_HASH_KEY = "rl:rules";

    private final HashOperations<String, String, String> hashOps;
    private final ObjectMapper objectMapper;

    public RateLimitRuleRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.hashOps = redisTemplate.opsForHash();
        this.objectMapper = objectMapper;
    }

    public Optional<RateLimitRule> findByClientId(String clientId) {
        String json = hashOps.get(RULES_HASH_KEY, clientId);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialize(json));
    }

    public void save(RateLimitRule rule) {
        hashOps.put(RULES_HASH_KEY, rule.getClientId(), serialize(rule));
    }

    public void delete(String clientId) {
        hashOps.delete(RULES_HASH_KEY, clientId);
    }

    public Map<String, RateLimitRule> findAll() {
        Map<String, String> raw = hashOps.entries(RULES_HASH_KEY);
        Map<String, RateLimitRule> rules = new LinkedHashMap<>();
        raw.forEach((clientId, json) -> rules.put(clientId, deserialize(json)));
        return rules;
    }

    private String serialize(RateLimitRule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rate limit rule for " + rule.getClientId(), e);
        }
    }

    private RateLimitRule deserialize(String json) {
        try {
            return objectMapper.readValue(json, RateLimitRule.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize rate limit rule: " + json, e);
        }
    }
}
