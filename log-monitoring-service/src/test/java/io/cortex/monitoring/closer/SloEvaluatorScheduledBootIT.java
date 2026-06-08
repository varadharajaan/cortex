package io.cortex.monitoring.closer;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.monitoring.slo.SloEngineConfig;
import io.cortex.monitoring.slo.SloEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestConstructor;

/**
 * Bootstrap regression IT for the issue #120 / LD137 prod fix
 * (ADR-0046 Amendment 2026-06-08).
 *
 * <p>Boots the full Spring context with
 * {@code cortex.monitoring.slo.enabled=true} (so the
 * {@link SloEvaluator} bean is created and its
 * {@code @Scheduled(fixedRateString=...)} declaration is
 * processed) AND with {@code cortex.monitoring.slo.evaluation-interval}
 * left at its operator-friendly default ({@code 30s} in
 * {@code application.yml}). Before the fix this combination failed
 * bean creation with
 * {@code NumberFormatException: For input string: "30s"} from
 * Spring's
 * {@code ScheduledAnnotationBeanPostProcessor.parseFixedRate} --
 * the symptom captured in issue #120.</p>
 *
 * <p>Post-fix, {@code SloEvaluator.@Scheduled(fixedRateString=
 * "#{@sloEvaluationIntervalMillis}")} reads its cadence through
 * the
 * {@link SloEngineConfig#sloEvaluationIntervalMillis(io.cortex.monitoring.slo.SloProperties)}
 * adapter bean (a {@code Long} of millis), which Spring's
 * scheduler accepts via {@code Long.parseLong} cleanly. This IT
 * therefore is the CI-protected proof that the operator-friendly
 * {@code Duration} form is safe under {@code slo.enabled=true}
 * and that the adapter bean is published under the canonical
 * name {@link SloEngineConfig#SLO_EVALUATION_INTERVAL_MILLIS_BEAN}
 * (so the SpEL string in the annotation and the bean cannot
 * drift).</p>
 *
 * <p>Intentionally lives in {@code closer} alongside the P8.1a /
 * P8.2a cross-phase ITs: the gap LD104 closer-separation
 * discipline highlights -- per-phase tests that construct
 * adapters via {@code new ...} bypass Spring binding and so
 * cannot surface bean-creation failures -- is exactly the gap
 * this IT plugs for the SLO surface's scheduled cadence wiring.
 * The companion {@link MonitoringProbeAndSloPipelineIT} (P8.2a)
 * also exercises the full both-gates-on context and is the
 * canonical broader regression; this IT is the narrowest
 * possible repro that pins ONLY the issue #120 fix so a future
 * regression points immediately at the cadence-wiring path.</p>
 *
 * <p>Eureka is disabled at the property level via the test
 * {@code application.yml} (LD100) so this IT does not need the
 * P8.2a {@code spring.autoconfigure.exclude} triplet -- it does
 * not autowire {@code DiscoveryClient} or stand up a WireMock
 * server. The probe backend stays at the {@code noop} default
 * so no HTTP traffic is attempted.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                // The cadence env-var is INTENTIONALLY NOT set so
                // the operator-friendly `30s` default from
                // src/test/resources/application.yml is exercised.
                // The acceptance criterion for issue #120 is
                // exactly: "SloEvaluator boots cleanly with
                // slo.enabled=true AND the operator-friendly 30s /
                // 1h / PT30S value forms".
                "cortex.monitoring.slo.enabled=true",
                "cortex.monitoring.slo.backend=noop"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("SloEvaluator scheduled bootstrap IT (issue #120 / LD137 SpEL fix)")
class SloEvaluatorScheduledBootIT {

    private final ApplicationContext context;
    private final SloEvaluator evaluator;

    SloEvaluatorScheduledBootIT(final ApplicationContext applicationContext,
                                final SloEvaluator sloEvaluator) {
        this.context = applicationContext;
        this.evaluator = sloEvaluator;
    }

    /**
     * Asserts that the {@link SloEvaluator} bean is created under
     * {@code slo.enabled=true} + the operator-friendly
     * {@code evaluation-interval=30s} default. Pre-fix this
     * threw {@code BeanCreationException} caused by
     * {@code NumberFormatException: For input string: "30s"}.
     */
    @Test
    @DisplayName("SloEvaluator wires cleanly under enabled=true + Duration cadence")
    void evaluatorBeanWiresUnderOperatorFriendlyDurationCadence() {
        assertThat(this.evaluator).isNotNull();
        assertThat(this.context.containsBean("sloEvaluator")).isTrue();
    }

    /**
     * Asserts the cadence adapter bean is published under the
     * canonical name pinned by
     * {@link SloEngineConfig#SLO_EVALUATION_INTERVAL_MILLIS_BEAN}
     * and resolves to the test-default cadence (
     * {@code 30s = 30 000 ms}). A drift between the SpEL string
     * in {@link SloEvaluator#evaluateAll()} and the bean name
     * registered in {@link SloEngineConfig} would manifest as a
     * boot-time bean-creation failure, but pinning the name via
     * the constant + asserting it here keeps the link explicit.
     */
    @Test
    @DisplayName("sloEvaluationIntervalMillis bean is registered under the canonical name + matches Duration default")
    void cadenceAdapterBeanIsRegisteredUnderCanonicalName() {
        assertThat(this.context.containsBean(
                SloEngineConfig.SLO_EVALUATION_INTERVAL_MILLIS_BEAN))
                .isTrue();
        final Long cadenceMillis = this.context.getBean(
                SloEngineConfig.SLO_EVALUATION_INTERVAL_MILLIS_BEAN,
                Long.class);
        // src/test/resources/application.yml ships
        // evaluation-interval: 30s -> 30 000 ms.
        assertThat(cadenceMillis).isEqualTo(30_000L);
    }
}
