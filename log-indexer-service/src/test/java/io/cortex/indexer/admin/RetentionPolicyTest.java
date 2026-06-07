package io.cortex.indexer.admin;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetentionPolicy} (P7.2 / ADR-0040 D2).
 *
 * <p>The record is intentionally strict on input: a missing or
 * non-positive TTL is a configuration bug that must surface at
 * bind/construction time, not at the first Quickwit call.</p>
 */
@DisplayName("RetentionPolicy")
class RetentionPolicyTest {

    @Test
    void positiveTtlIsAccepted() {
        final RetentionPolicy policy =
                new RetentionPolicy(Duration.ofDays(7));
        assertThat(policy.ttl()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void nullTtlIsRejected() {
        assertThatThrownBy(() -> new RetentionPolicy(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    void zeroTtlIsRejected() {
        assertThatThrownBy(() -> new RetentionPolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }

    @Test
    void negativeTtlIsRejected() {
        assertThatThrownBy(
                () -> new RetentionPolicy(Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }
}
