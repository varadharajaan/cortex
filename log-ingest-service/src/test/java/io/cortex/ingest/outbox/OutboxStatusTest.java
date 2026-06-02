package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Lightweight enum smoke test for {@link OutboxStatus} (P4.4a /
 * ADR-0025). Pins the three lifecycle values and the
 * {@link Enum#name()} contract that the V3 Flyway
 * {@code CHECK ... IN ('PENDING','PUBLISHED','FAILED')} relies on.
 */
class OutboxStatusTest {

    /** Default constructor used by JUnit. */
    OutboxStatusTest() {
        // no state
    }

    /** Pins enum cardinality so a stray value is caught at build time. */
    @Test
    void hasExactlyThreeLifecycleStates() {
        assertThat(OutboxStatus.values()).hasSize(3);
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
    }
}
