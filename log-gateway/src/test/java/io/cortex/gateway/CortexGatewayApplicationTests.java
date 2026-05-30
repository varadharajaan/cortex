package io.cortex.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full Spring application context; fails if any bean is
 * misconfigured (rule A14.1 - smoke test on context load).
 */
@SpringBootTest
@ActiveProfiles("dev")
class CortexGatewayApplicationTests {

    /**
     * No-op test body; the {@link SpringBootTest} bootstrap itself is
     * the assertion.
     */
    @Test
    void contextLoads() {
        // intentionally empty
    }
}
