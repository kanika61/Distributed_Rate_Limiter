package com.ratelimiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Dummy downstream endpoint the rate limit filter protects. In a real service this would
 * be actual business logic; here it just proves a request made it through the filter.
 */
@RestController
public class ResourceController {

    @GetMapping("/api/resource")
    public Map<String, Object> getResource() {
        return Map.of(
                "message", "Here is your resource.",
                "timestamp", Instant.now().toString()
        );
    }
}
