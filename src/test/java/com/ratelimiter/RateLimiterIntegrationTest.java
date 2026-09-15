package com.ratelimiter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack test: real Spring context, real embedded Tomcat, real Redis (via
 * Testcontainers) sitting behind the filter -> controller chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RateLimiterIntegrationTest extends AbstractRedisIntegrationTest {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    private ResponseEntity<String> callResource(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-API-Key", clientId);
        return restTemplate.exchange(
                "http://localhost:" + port + "/api/resource",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    @Test
    void allowsRequestsUpToTheConfiguredLimitThenReturns429() {
        String clientId = "itest-" + UUID.randomUUID();

        // application-test.yml sets the default rule's capacity/limit to 5
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = callResource(clientId);
            assertThat(response.getStatusCode()).as("request %d", i).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> sixth = callResource(clientId);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(sixth.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(sixth.getBody()).contains("rate_limit_exceeded");
    }

    @Test
    void adminApiIsNotRateLimited() {
        for (int i = 0; i < 20; i++) {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    "http://localhost:" + port + "/admin/rules/default", String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void adminApiCanCreatePerClientRuleThatOverridesDefault() {
        String clientId = "itest-custom-" + UUID.randomUUID();
        String ruleJson = """
                {"algorithm":"TOKEN_BUCKET","capacity":2,"refillRatePerSecond":1,"limit":2,"windowSeconds":1}
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> put = restTemplate.exchange(
                "http://localhost:" + port + "/admin/rules/" + clientId,
                HttpMethod.PUT,
                new HttpEntity<>(ruleJson, headers),
                String.class
        );
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Custom rule allows only 2 (lower than the default's 5)
        assertThat(callResource(clientId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callResource(clientId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callResource(clientId).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
