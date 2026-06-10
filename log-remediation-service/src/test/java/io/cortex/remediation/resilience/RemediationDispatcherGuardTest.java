package io.cortex.remediation.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.remediation.dispatch.DispatchResult;
import io.cortex.remediation.dispatch.RemediationDispatcher;
import io.cortex.remediation.parse.AnomalyEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the programmatic Resilience4j dispatcher guard.
 */
class RemediationDispatcherGuardTest {

    /** Disabled guard must pass through with no retry/circuit decoration. */
    @Test
    void disabledGuardCallsDispatcherOnce() {
        final CountingDispatcher dispatcher =
                new CountingDispatcher(DispatchResult.dispatched("slack"));
        final RemediationDispatcherGuard guard = guard(false, 2, 2, 1);

        final DispatchResult result = guard.dispatch(dispatcher, event());

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);
        assertThat(dispatcher.calls()).isEqualTo(1);
    }

    /** Transient dispatcher verdicts are retried before returning success. */
    @Test
    void transientFailuresAreRetriedUntilSuccess() {
        final CountingDispatcher dispatcher = new CountingDispatcher(
                DispatchResult.transientFailure("slack", "slack:500"),
                DispatchResult.transientFailure("slack", "slack:500"),
                DispatchResult.dispatched("slack"));
        final RemediationDispatcherGuard guard = guard(true, 3, 10, 10);

        final DispatchResult result = guard.dispatch(dispatcher, event());

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_DISPATCHED);
        assertThat(dispatcher.calls()).isEqualTo(3);
    }

    /** Enough transient failures open the circuit and shed the next call. */
    @Test
    void circuitOpenReturnsTransientFailureWithoutCallingDispatcher() {
        final CountingDispatcher dispatcher = new CountingDispatcher(
                DispatchResult.transientFailure("slack", "slack:500"),
                DispatchResult.transientFailure("slack", "slack:500"),
                DispatchResult.dispatched("slack"));
        final RemediationDispatcherGuard guard = guard(true, 1, 2, 2);

        guard.dispatch(dispatcher, event());
        guard.dispatch(dispatcher, event());
        final DispatchResult result = guard.dispatch(dispatcher, event());

        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("slack:circuit-open");
        assertThat(dispatcher.calls()).isEqualTo(2);
    }

    /** Runtime exceptions are converted to bounded transient outcomes. */
    @Test
    void dispatcherExceptionReturnsTransientFailure() {
        final RemediationDispatcher dispatcher = new RemediationDispatcher() {
            @Override
            public String channelId() {
                return "jira";
            }

            @Override
            public DispatchResult dispatch(final AnomalyEvent event) {
                throw new IllegalStateException("client closed");
            }
        };
        final RemediationDispatcherGuard guard = guard(true, 1, 10, 10);

        final DispatchResult result = guard.dispatch(dispatcher, event());

        assertThat(result.channel()).isEqualTo("jira");
        assertThat(result.outcome())
                .isEqualTo(DispatchResult.OUTCOME_TRANSIENT_FAILURE);
        assertThat(result.reason()).isEqualTo("jira:exception");
    }

    /** Null dispatcher is a skipped no-op. */
    @Test
    void nullDispatcherReturnsSkipped() {
        final DispatchResult result = guard(true, 1, 2, 1)
                .dispatch(null, event());

        assertThat(result.outcome()).isEqualTo(DispatchResult.OUTCOME_SKIPPED);
        assertThat(result.reason()).isEqualTo("dispatcher:null");
    }

    private static RemediationDispatcherGuard guard(
            final boolean enabled,
            final int retryAttempts,
            final int slidingWindowSize,
            final int minimumNumberOfCalls) {
        return new RemediationDispatcherGuard(enabled, 50.0f,
                slidingWindowSize, minimumNumberOfCalls, Duration.ofMinutes(1),
                1, retryAttempts, Duration.ZERO);
    }

    private static AnomalyEvent event() {
        return new AnomalyEvent("evt-1", "tenant-a", "HIGH", "reason",
                Instant.parse("2026-06-09T00:00:00Z"), "ERROR", "checkout",
                "boom", 0.8d, "BURST", "restart-service");
    }

    private static final class CountingDispatcher implements RemediationDispatcher {
        private final Queue<DispatchResult> results = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();

        private CountingDispatcher(final DispatchResult... results) {
            this.results.addAll(java.util.List.of(results));
        }

        @Override
        public String channelId() {
            return "slack";
        }

        @Override
        public DispatchResult dispatch(final AnomalyEvent event) {
            this.calls.incrementAndGet();
            final DispatchResult next = this.results.poll();
            return next == null ? DispatchResult.dispatched("slack") : next;
        }

        private int calls() {
            return this.calls.get();
        }
    }
}
