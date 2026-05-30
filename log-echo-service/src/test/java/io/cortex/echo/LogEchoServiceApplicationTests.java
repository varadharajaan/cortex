package io.cortex.echo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full Spring application context; fails if any bean is
 * misconfigured (rule A14.1 - smoke test on context load).
 */
@SpringBootTest
class LogEchoServiceApplicationTests {

    /**
     * No-op test body; the {@link SpringBootTest} bootstrap itself is
     * the assertion.
     */
    @Test
    void contextLoads() {
        // intentionally empty
    }
}
