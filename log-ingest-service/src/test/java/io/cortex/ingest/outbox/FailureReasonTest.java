package io.cortex.ingest.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FailureReason} (P4.4c / ADR-0027 D4).
 *
 * <p>Pins the throwable-to-tag mapping so the Prometheus reason
 * tag stays inside the documented allowlist for every exception
 * the poller could observe.</p>
 */
class FailureReasonTest {

    /** Default constructor used by JUnit. */
    FailureReasonTest() {
        // no state
    }

    /** {@link TimeoutException} maps to {@code kafka.timeout}. */
    @Test
    void timeoutMapsToKafkaTimeout() {
        assertThat(FailureReason.fromThrowable(new TimeoutException("ack timeout")))
                .isEqualTo(FailureReason.KAFKA_TIMEOUT);
    }

    /** {@link InterruptedException} maps to {@code kafka.interrupted}. */
    @Test
    void interruptedMapsToKafkaInterrupted() {
        assertThat(FailureReason.fromThrowable(new InterruptedException()))
                .isEqualTo(FailureReason.KAFKA_INTERRUPTED);
    }

    /** Generic {@link ExecutionException} maps to {@code kafka.execute}. */
    @Test
    void executionExceptionMapsToKafkaExecute() {
        assertThat(FailureReason.fromThrowable(
                new ExecutionException(new RuntimeException("broker"))))
                .isEqualTo(FailureReason.KAFKA_EXECUTE);
    }

    /** Anything under {@code org.apache.kafka} also maps to {@code kafka.execute}. */
    @Test
    void apacheKafkaExceptionMapsToKafkaExecute() {
        final Throwable ex = new org.apache.kafka.common.errors.NotLeaderOrFollowerException(
                "leader unavailable");
        assertThat(FailureReason.fromThrowable(ex))
                .isEqualTo(FailureReason.KAFKA_EXECUTE);
    }

    /** Unmapped exception classes fall to {@code unknown}. */
    @Test
    void unknownExceptionMapsToUnknown() {
        assertThat(FailureReason.fromThrowable(new IllegalArgumentException("nope")))
                .isEqualTo(FailureReason.UNKNOWN);
    }

    /** Null input maps to {@code unknown}. */
    @Test
    void nullThrowableMapsToUnknown() {
        assertThat(FailureReason.fromThrowable(null))
                .isEqualTo(FailureReason.UNKNOWN);
    }
}
