package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Lightweight enum smoke test for {@link OutboxStatus} (P4.4a /
 * ADR-0025 + P4.4c / ADR-0027). Pins the four lifecycle values
 * and the {@link Enum#name()} contract that the V4 Flyway
 * {@code CHECK ... IN ('PENDING','PUBLISHED','FAILED','DEAD')}
 * relies on.
 */
class OutboxStatusTest {

    /** Default constructor used by JUnit. */
    OutboxStatusTest() {
        // no state
    }

    /** Pins enum cardinality so a stray value is caught at build time. */
    @Test
    void hasExactlyFourLifecycleStates() {
        assertThat(OutboxStatus.values()).hasSize(4);
    }

    /**
     * Pins the persisted spelling so a careless rename does not
     * desync the Java enum from the Postgres CHECK constraint
     * (which would silently break the outbox).
     */
    @Test
    void persistedNamesMatchFlywayCheckConstraint() {
        assertThat(OutboxStatus.PENDING.name()).isEqualTo("PENDING");
        assertThat(OutboxStatus.PUBLISHED.name()).isEqualTo("PUBLISHED");
        assertThat(OutboxStatus.FAILED.name()).isEqualTo("FAILED");
        assertThat(OutboxStatus.DEAD.name()).isEqualTo("DEAD");
    }
}
