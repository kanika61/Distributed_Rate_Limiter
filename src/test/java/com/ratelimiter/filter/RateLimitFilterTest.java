package com.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private FilterChain filterChain;

    private RateLimiterProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimiterProperties();
        filter = new RateLimitFilter(rateLimiterService, properties, new ObjectMapper());
    }

    @Test
    void allowsRequestAndForwardsDownstreamWhenUnderLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resource");
        request.addHeader("X-API-Key", "client-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume("client-1")).thenReturn(RateLimitResult.allow(4));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("4");
    }

    @Test
    void returns429WithRetryAfterHeaderWhenOverLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resource");
        request.addHeader("X-API-Key", "client-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume("client-1")).thenReturn(RateLimitResult.deny(2500));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3"); // ceil(2500ms) = 3s
        assertThat(response.getContentAsString()).contains("rate_limit_exceeded");
    }

    @Test
    void fallsBackToRemoteAddressWhenClientHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resource");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume("ip:10.0.0.5")).thenReturn(RateLimitResult.allow(1));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getHeader("X-RateLimit-Client")).isEqualTo("ip:10.0.0.5");
    }

    @Test
    void bypassesExcludedPathsEntirely() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/rules");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }
}
