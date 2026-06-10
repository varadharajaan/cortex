package io.cortex.monitoring.slo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SloDefinition} (P8.2 / ADR-0046 D1).
 *
 * <p>Locks the compact-ctor validation contract: null/blank
 * serviceId or sloName rejected; targetSuccessRatio must sit in
 * the open interval {@code (0, 1)} (boundaries 0.0 and 1.0
 * rejected); window must be a positive {@link Duration}. Every
 * boundary case has a dedicated assertion so a regression on the
 * compact ctor light up immediately.</p>
 */
class SloDefinitionTest {

    @Test
    void canonicalDefinitionWires() {
        final SloDefinition def = new SloDefinition(
                "log-indexer-service", "availability",
                0.99d, Duration.ofHours(1), null);
        assertThat(def.serviceId()).isEqualTo("log-indexer-service");
        assertThat(def.sloName()).isEqualTo("availability");
        assertThat(def.targetSuccessRatio()).isEqualTo(0.99d);
        assertThat(def.window()).isEqualTo(Duration.ofHours(1));
        assertThat(def.counterFamily()).isNull();
        assertThat(def.timer()).isNull();
        assertThat(def.promQl()).isNull();
        assertThat(def.composite()).isNull();
        assertThat(def.otel()).isNull();
    }

    @Test
    void counterFamilyDefinitionWires() {
        final SloDefinition.TagPredicate success =
                new SloDefinition.TagPredicate("outcome",
                        List.of("dispatched"));
        final SloDefinition.TagPredicate failure =
                new SloDefinition.TagPredicate("outcome",
                        List.of("transient_failure", "permanent_failure"));
        final SloDefinition.CounterFamilySource source =
                new SloDefinition.CounterFamilySource(
                        "cortex.remediation.dispatched_total",
                        success, failure, Map.of("channel", "slack"));

        final SloDefinition def = new SloDefinition(
                "log-remediation-service", "slack-dispatch-success",
                0.99d, Duration.ofHours(1), source);

        assertThat(def.counterFamily()).isEqualTo(source);
        assertThat(def.counterFamily().requiredTags())
                .containsEntry("channel", "slack");
        assertThat(def.counterFamily().successTagPredicate()
                .matches(Map.of("outcome", "dispatched"))).isTrue();
        assertThat(def.counterFamily().failureTagPredicate()
                .matches(Map.of("outcome", "transient_failure"))).isTrue();
    }

    @Test
    void nullServiceIdRejected() {
        assertThatThrownBy(() -> new SloDefinition(null, "availability",
                0.99d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void blankServiceIdRejected() {
        assertThatThrownBy(() -> new SloDefinition("  ", "availability",
                0.99d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId");
    }

    @Test
    void blankSloNameRejected() {
        assertThatThrownBy(() -> new SloDefinition("log-indexer-service",
                "", 0.99d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sloName");
    }

    @Test
    void targetExactlyZeroRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.0d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetExactlyOneRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                1.0d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetNegativeRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                -0.1d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void targetAboveOneRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                1.5d, Duration.ofHours(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetSuccessRatio");
    }

    @Test
    void nullWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void zeroWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void negativeWindowRejected() {
        assertThatThrownBy(() -> new SloDefinition("svc", "a",
                0.5d, Duration.ofSeconds(-5), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
    }

    @Test
    void blankCounterFamilyMetricNameRejected() {
        assertThatThrownBy(() -> new SloDefinition.CounterFamilySource(
                " ", predicate(), predicate(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricName");
    }

    @Test
    void nullSuccessPredicateRejected() {
        assertThatThrownBy(() -> new SloDefinition.CounterFamilySource(
                "metric", null, predicate(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successTagPredicate");
    }

    @Test
    void nullFailurePredicateRejected() {
        assertThatThrownBy(() -> new SloDefinition.CounterFamilySource(
                "metric", predicate(), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureTagPredicate");
    }

    @Test
    void blankTagPredicateNameRejected() {
        assertThatThrownBy(() -> new SloDefinition.TagPredicate(
                " ", List.of("ok")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tagName");
    }

    @Test
    void emptyTagPredicateValuesRejected() {
        assertThatThrownBy(() -> new SloDefinition.TagPredicate(
                "outcome", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tagValues");
    }

    @Test
    void blankRequiredTagRejected() {
        assertThatThrownBy(() -> new SloDefinition.CounterFamilySource(
                "metric", predicate(), predicate(), Map.of(" ", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requiredTags");
    }

    @Test
    void timerSourceWiresAndCopiesTags() {
        final SloDefinition.TimerSource source =
                new SloDefinition.TimerSource(
                        "http.server.requests.seconds",
                        Duration.ofMillis(300),
                        Map.of("uri", "/api/v1/logs"));

        final SloDefinition def = new SloDefinition(
                "log-gateway", "gateway-route-latency-p95",
                0.95d, Duration.ofMinutes(5),
                null, source, null, null, null);

        assertThat(def.timer()).isEqualTo(source);
        assertThat(def.timer().requiredTags())
                .containsEntry("uri", "/api/v1/logs");
    }

    @Test
    void invalidTimerSourceRejected() {
        assertThatThrownBy(() -> new SloDefinition.TimerSource(
                " ", Duration.ofMillis(300), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricName");
        assertThatThrownBy(() -> new SloDefinition.TimerSource(
                "timer", Duration.ZERO, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void promQlSourceWires() {
        final SloDefinition.PromQlSource source =
                new SloDefinition.PromQlSource(
                        "sum(rate(success_total[5m]))",
                        "sum(rate(failure_total[5m]))");

        final SloDefinition def = new SloDefinition(
                "log-indexer-service", "quickwit-search-success",
                0.99d, Duration.ofMinutes(5),
                null, null, source, null, null);

        assertThat(def.promQl()).isEqualTo(source);
    }

    @Test
    void blankPromQlQueryRejected() {
        assertThatThrownBy(() -> new SloDefinition.PromQlSource(
                " ", "sum(failure_total)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("successQuery");
        assertThatThrownBy(() -> new SloDefinition.PromQlSource(
                "sum(success_total)", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureQuery");
    }

    @Test
    void compositeSourceWiresDefaultModeAndComponents() {
        final SloDefinition.CompositeSource source =
                new SloDefinition.CompositeSource(
                        " ",
                        List.of(new SloDefinition.ComponentRef(
                                "log-gateway", "availability", 1.0d)));

        final SloDefinition def = new SloDefinition(
                "cortex-system", "system-availability",
                0.99d, Duration.ofMinutes(5),
                null, null, null, source, null);

        assertThat(def.composite().mode())
                .isEqualTo(SloDefinition.CompositeSource.MODE_WORST_OF);
        assertThat(def.composite().components()).hasSize(1);
    }

    @Test
    void invalidCompositeSourceRejected() {
        assertThatThrownBy(() -> new SloDefinition.CompositeSource(
                "median", List.of(new SloDefinition.ComponentRef(
                "svc", "availability", 1.0d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        assertThatThrownBy(() -> new SloDefinition.CompositeSource(
                SloDefinition.CompositeSource.MODE_WORST_OF, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("components");
        assertThatThrownBy(() -> new SloDefinition.ComponentRef(
                "svc", "availability", 0.0d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");
    }

    @Test
    void otelSourceWires() {
        final SloDefinition.OtelSource source =
                new SloDefinition.OtelSource(
                        "otel.span.server.requests_total",
                        predicate(),
                        new SloDefinition.TagPredicate("status_code",
                                List.of("ERROR")),
                        Map.of("span_kind", "server"));

        final SloDefinition def = new SloDefinition(
                "log-gateway", "server-span-success",
                0.98d, Duration.ofMinutes(5),
                null, null, null, null, source);

        assertThat(def.otel()).isEqualTo(source);
        assertThat(def.otel().requiredTags())
                .containsEntry("span_kind", "server");
    }

    private static SloDefinition.TagPredicate predicate() {
        return new SloDefinition.TagPredicate("outcome", List.of("ok"));
    }
}
