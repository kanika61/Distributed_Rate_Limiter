package com.ratelimiter.controller;

import com.ratelimiter.model.RateLimitRule;
import com.ratelimiter.service.RuleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Admin API for managing per-client rate limit rules and the default/global fallback rule.
 * Not itself rate limited -- see {@code ratelimiter.excluded-paths} in application.yml.
 */
@RestController
@RequestMapping("/admin/rules")
public class AdminController {

    private final RuleService ruleService;

    public AdminController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    public Map<String, RateLimitRule> listRules() {
        return ruleService.getAllClientRules();
    }

    @GetMapping("/default")
    public RateLimitRule getDefaultRule() {
        return ruleService.getDefaultRule();
    }

    @PutMapping("/default")
    public RateLimitRule setDefaultRule(@Valid @RequestBody RateLimitRule rule) {
        ruleService.setDefaultRule(rule);
        return ruleService.getDefaultRule();
    }

    @GetMapping("/{clientId}")
    public RateLimitRule getRule(@PathVariable String clientId) {
        return ruleService.getRule(clientId);
    }

    @PutMapping("/{clientId}")
    public RateLimitRule upsertRule(@PathVariable String clientId, @Valid @RequestBody RateLimitRule rule) {
        rule.setClientId(clientId);
        ruleService.upsertRule(rule);
        return ruleService.getRule(clientId);
    }

    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> deleteRule(@PathVariable String clientId) {
        ruleService.deleteRule(clientId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
