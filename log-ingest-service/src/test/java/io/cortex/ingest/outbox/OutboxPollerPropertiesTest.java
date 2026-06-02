package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxPollerProperties} (P4.4b / ADR-0026).
 *
 * <p>Exercises the {@link OutboxPollerProperties.PollerProps#nextBackoff(int)}
 * exponential-doubling helper because the production
 * {@link OutboxPoller} relies on its monotonic, capped behaviour
 * for retry scheduling.</p>
 */
class OutboxPollerPropertiesTest {

    /** Default constructor used by JUnit. */
    OutboxPollerPropertiesTest() {
        // no state
    }

    /**
     * First failure returns the initial backoff verbatim, then
     * the delay doubles per failure until the configured cap is
     * reached and never exceeded.
     */
    @Test
    void nextBackoffDoublesAndCaps() {
        final OutboxPollerProperties.PollerProps props =
                new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 250L, 4_000L);

        assertThat(props.nextBackoff(1)).isEqualTo(Duration.ofMillis(250));
        assertThat(props.nextBackoff(2)).isEqualTo(Duration.ofMillis(500));
        assertThat(props.nextBackoff(3)).isEqualTo(Duration.ofMillis(1_000));
        assertThat(props.nextBackoff(4)).isEqualTo(Duration.ofMillis(2_000));
        assertThat(props.nextBackoff(5)).isEqualTo(Duration.ofMillis(4_000));
        assertThat(props.nextBackoff(6)).isEqualTo(Duration.ofMillis(4_000));
        assertThat(props.nextBackoff(20)).isEqualTo(Duration.ofMillis(4_000));
    }

    /**
     * A pathologically large attempt count (or a misconfigured
     * {@code backoffInitialMs == 0}) must not produce a negative
     * duration via {@code long} overflow; the helper clamps to the
     * configured ceiling.
     */
    @Test
    void nextBackoffDoesNotOverflowOnHugeAttemptCounts() {
        final OutboxPollerProperties.PollerProps props =
                new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 0L, 60_000L);

        final Duration capped = props.nextBackoff(1_000);

        assertThat(capped).isEqualTo(Duration.ofMillis(60_000));
    }

    /**
     * When {@code backoffMaxMs < backoffInitialMs} the helper
     * never returns a value below the initial backoff (a
     * misconfigured cap cannot shrink the first-failure delay).
     */
    @Test
    void nextBackoffFloorsAtInitialWhenCapIsTooSmall() {
        final OutboxPollerProperties.PollerProps props =
                new OutboxPollerProperties.PollerProps(true, 1_000L, 100, 500L, 100L);

        assertThat(props.nextBackoff(1)).isEqualTo(Duration.ofMillis(500));
    }
}
