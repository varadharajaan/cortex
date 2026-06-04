package io.cortex.remediation.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoopRemediationDispatcher} (P6.0).
 *
 * <p>The Noop dispatcher is contractually the {@code matchIfMissing=
 * true} default; every event must produce a {@code skipped} verdict
 * on the {@code noop} channel with a non-blank reason so the
 * downstream {@code RemediationMetrics.incDispatched(...)} call sees
 * bounded tag values per Part 17.</p>
 */
class NoopRemediationDispatcherTest {

    /**
     * Builds a representative {@link AnomalyEvent} for dispatcher input.
     *
     * @return a fully populated {@link AnomalyEvent} sample
     */
    private static AnomalyEvent sampleEvent() {
        return new AnomalyEvent(
                "evt-12345",
                "tenant-abc",
                "HIGH",
                "checkout 5xx burst",
                Instant.parse("2025-06-04T09:40:30Z"),
                "ERROR",
                "checkout",
                "503 from /pay endpoint");
    }

    /** The default no-op dispatcher must report {@code channel=noop, outcome=skipped} and a non-blank reason. */
    @Test
    void dispatchReturnsSkippedOnNoopChannel() {
        final NoopRemediationDispatcher dispatcher = new NoopRemediationDispatcher();
        final DispatchResult result = dispatcher.dispatch(sampleEvent());

        assertThat(result).isNotNull();
        assertThat(result.dispatched()).isFalse();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_NOOP);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isNotBlank();
    }

    /** {@link DispatchResult#skipped(String)} must defend against a null reason argument with an empty string. */
    @Test
    void dispatchResultSkippedFactoryHandlesNullReason() {
        final DispatchResult result = DispatchResult.skipped(null);

        assertThat(result.dispatched()).isFalse();
        assertThat(result.channel()).isEqualTo(DispatchResult.CHANNEL_NOOP);
        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEmpty();
    }
}
