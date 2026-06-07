package io.cortex.monitoring.probe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link HealthSnapshot} value object (P8.0).
 * Exercises every factory method + the constant surface so the
 * JaCoCo line + branch budget on the record body is met.
 */
class HealthSnapshotTest {

    @Test
    void constantsAreStable() {
        assertThat(HealthSnapshot.BACKEND_NOOP).isEqualTo("noop");
        assertThat(HealthSnapshot.BACKEND_EUREKA_ACTUATOR)
                .isEqualTo("eureka-actuator");
        assertThat(HealthSnapshot.OUTCOME_NOOP).isEqualTo("noop");
        assertThat(HealthSnapshot.OUTCOME_HEALTHY).isEqualTo("healthy");
        assertThat(HealthSnapshot.OUTCOME_DEGRADED).isEqualTo("degraded");
        assertThat(HealthSnapshot.OUTCOME_UNHEALTHY).isEqualTo("unhealthy");
        assertThat(HealthSnapshot.OUTCOME_UNREACHABLE)
                .isEqualTo("unreachable");
        assertThat(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE)
                .isEqualTo("transient_failure");
        assertThat(HealthSnapshot.OUTCOME_PERMANENT_FAILURE)
                .isEqualTo("permanent_failure");
    }

    @Test
    void noopFactoryStampsNoopBackendAndOutcome() {
        final HealthSnapshot r = HealthSnapshot.noop("scaffold-default");
        assertThat(r.backend()).isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(r.outcome()).isEqualTo(HealthSnapshot.OUTCOME_NOOP);
        assertThat(r.reason()).isEqualTo("scaffold-default");
        assertThat(r.detail()).isEmpty();
    }

    @Test
    void noopFactoryCoercesNullReasonToEmpty() {
        final HealthSnapshot r = HealthSnapshot.noop(null);
        assertThat(r.reason()).isEmpty();
        assertThat(r.detail()).isEmpty();
    }

    @Test
    void healthyFactoryStampsHealthyOutcome() {
        final HealthSnapshot r = HealthSnapshot.healthy(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, "UP");
        assertThat(r.backend())
                .isEqualTo(HealthSnapshot.BACKEND_EUREKA_ACTUATOR);
        assertThat(r.outcome()).isEqualTo(HealthSnapshot.OUTCOME_HEALTHY);
        assertThat(r.reason()).isEmpty();
        assertThat(r.detail()).isEqualTo("UP");
    }

    @Test
    void degradedFactoryStampsDegradedOutcome() {
        final HealthSnapshot r = HealthSnapshot.degraded(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, "OUT_OF_SERVICE");
        assertThat(r.outcome()).isEqualTo(HealthSnapshot.OUTCOME_DEGRADED);
        assertThat(r.detail()).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void unhealthyFactoryStampsUnhealthyOutcome() {
        final HealthSnapshot r = HealthSnapshot.unhealthy(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, "DOWN");
        assertThat(r.outcome()).isEqualTo(HealthSnapshot.OUTCOME_UNHEALTHY);
        assertThat(r.detail()).isEqualTo("DOWN");
    }

    @Test
    void unreachableFactoryStampsUnreachableOutcome() {
        final HealthSnapshot r = HealthSnapshot.unreachable(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                "eureka-actuator:connect-refused");
        assertThat(r.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_UNREACHABLE);
        assertThat(r.reason())
                .isEqualTo("eureka-actuator:connect-refused");
    }

    @Test
    void transientFailureFactoryStampsTransientOutcome() {
        final HealthSnapshot r = HealthSnapshot.transientFailure(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                "eureka-actuator:5xx:503");
        assertThat(r.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_TRANSIENT_FAILURE);
        assertThat(r.reason()).isEqualTo("eureka-actuator:5xx:503");
    }

    @Test
    void permanentFailureFactoryStampsPermanentOutcome() {
        final HealthSnapshot r = HealthSnapshot.permanentFailure(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR,
                "eureka-actuator:4xx:404");
        assertThat(r.outcome())
                .isEqualTo(HealthSnapshot.OUTCOME_PERMANENT_FAILURE);
        assertThat(r.reason()).isEqualTo("eureka-actuator:4xx:404");
    }

    @Test
    void factoriesCoerceNullBackendToNoop() {
        assertThat(HealthSnapshot.healthy(null, "x").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(HealthSnapshot.degraded(null, "x").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(HealthSnapshot.unhealthy(null, "x").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(HealthSnapshot.unreachable(null, "r").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(HealthSnapshot.transientFailure(null, "r").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
        assertThat(HealthSnapshot.permanentFailure(null, "r").backend())
                .isEqualTo(HealthSnapshot.BACKEND_NOOP);
    }

    @Test
    void factoriesCoerceNullDetailAndReasonToEmpty() {
        assertThat(HealthSnapshot.healthy(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).detail())
                .isEmpty();
        assertThat(HealthSnapshot.degraded(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).detail())
                .isEmpty();
        assertThat(HealthSnapshot.unhealthy(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).detail())
                .isEmpty();
        assertThat(HealthSnapshot.unreachable(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).reason())
                .isEmpty();
        assertThat(HealthSnapshot.transientFailure(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).reason())
                .isEmpty();
        assertThat(HealthSnapshot.permanentFailure(
                HealthSnapshot.BACKEND_EUREKA_ACTUATOR, null).reason())
                .isEmpty();
    }
}
