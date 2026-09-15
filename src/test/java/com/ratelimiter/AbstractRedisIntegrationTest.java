package com.ratelimiter;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base class for tests that need a real Redis instance. A single container is shared
 * across all subclasses' test runs within the JVM to keep the suite fast.
 */
@Testcontainers
public abstract class AbstractRedisIntegrationTest {

    private static final Logger REDIS_LOG = LoggerFactory.getLogger("REDIS_CONTAINER");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            // Wait for Redis to actually log that it's ready to serve, not just for the
            // port to be open -- on loaded CI runners the port can accept TCP connections
            // slightly before the server is ready to answer commands, which otherwise
            // shows up as the first real command timing out.
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)))
            // Stream the container's own log output into the test run's log so a command
            // timeout can be cross-referenced against what Redis itself was doing/logging
            // at that moment, instead of debugging blind from just the client-side exception.
            .withLogConsumer(new Slf4jLogConsumer(REDIS_LOG));
    // Deliberately NOT using .withReuse(true): reuse only takes effect when the
    // environment opts in via ~/.testcontainers.properties (testcontainers.reuse.enable=
    // true), which CI runners don't set. Requesting it without that flag is a known source
    // of inconsistent behavior across Testcontainers versions (reuse-requested containers
    // are handled differently, e.g. not registered with the Ryuk cleanup reaper), and CI
    // runners are ephemeral anyway so there's nothing to reuse across runs.

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // CI runners can be slow/throttled; give commands more room than the 2s default
        // used for the "real" app config so a slow-but-healthy container isn't treated
        // as a failure.
        registry.add("spring.data.redis.timeout", () -> "20000ms");
        registry.add("spring.data.redis.connect-timeout", () -> "20000ms");
    }

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
