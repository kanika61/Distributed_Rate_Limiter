package com.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the configured rate limit to every incoming request before it reaches a
 * downstream handler. Requests over the limit get HTTP 429 with a {@code Retry-After}
 * header; everything else is untouched and proceeds down the filter chain.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RateLimiterService rateLimiterService;
    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiterService rateLimiterService,
                            RateLimiterProperties properties,
                            ObjectMapper objectMapper) {
        this.rateLimiterService = rateLimiterService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return properties.getExcludedPaths().stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String clientId = resolveClientId(request);
        RateLimitResult result = rateLimiterService.tryConsume(clientId);

        response.setHeader("X-RateLimit-Client", clientId);

        if (!result.allowed()) {
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(result.retryAfterMs() / 1000.0));
            response.setStatus(429); // HTTP 429 Too Many Requests (not in the Servlet API's SC_ constants)
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "rate_limit_exceeded");
            body.put("message", "Too many requests for client '" + clientId + "'. Retry after " + retryAfterSeconds + "s.");
            body.put("retryAfterSeconds", retryAfterSeconds);
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        filterChain.doFilter(request, response);
    }

    private String resolveClientId(HttpServletRequest request) {
        String header = request.getHeader(properties.getClientHeader());
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        // Unrecognized/anonymous callers still get rate limited, keyed by remote address,
        // and fall back to the default/global rule since no explicit rule exists for them.
        return "ip:" + request.getRemoteAddr();
    }
}
