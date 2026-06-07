package io.cortex.monitoring.slo;

/**
 * Service Provider Interface for computing SLO budget remaining +
 * burn-rate gauges from the upstream probe counter surface
 * (P8.2 / ADR-0046 D1).
 *
 * <p>Implementations decide WHERE the input data comes from: the
 * default {@link NoopSloBudgetEngine} returns
 * {@link SloSnapshot#noop(SloDefinition)} for every call so the
 * scaffold runs end-to-end without any SLO derivation;
 * {@link MicrometerSloBudgetEngine} reads
 * {@code cortex.monitoring.probe_total} counter snapshots from
 * the in-process {@code MeterRegistry}. Future backends could
 * layer Prometheus query / native histogram / OpenTelemetry
 * inputs behind the same SPI without touching
 * {@link SloEvaluator}.</p>
 *
 * <p>Selection at runtime is driven by the
 * {@code cortex.monitoring.slo.backend} property +
 * {@code @ConditionalOnProperty} on each implementation. Exactly
 * one engine bean is active in a given profile.</p>
 *
 * <p>Implementations MUST be thread-safe. The engine surface is
 * called from {@link SloEvaluator}'s scheduled task concurrently
 * with other engine instances.</p>
 *
 * <p>Implementations MUST NOT throw -- every error path funnels
 * into the {@link SloSnapshot} envelope via
 * {@link SloSnapshot#transientFailure(String, SloDefinition, String)}
 * or {@link SloSnapshot#permanentFailure(String, SloDefinition, String)}.
 * The contract is symmetric with {@link io.cortex.monitoring.probe.ServiceHealthProbe}
 * (ADR-0044 D6),
 * {@code io.cortex.indexer.admin.QuickwitIndexAdmin} (ADR-0038
 * D6), and {@code io.cortex.remediation.dispatcher.RemediationDispatcher}
 * (ADR-0032 D6).</p>
 */
public interface SloBudgetEngine {

    /**
     * Stable backend identifier used by {@link MonitoringMetrics}
     * gauges to tag the per-engine surface and by
     * {@link SloSnapshot}'s backend field.
     *
     * @return the backend id; one of the
     *         {@link SloSnapshot}{@code .BACKEND_*} constants
     *         ({@code noop}, {@code micrometer-derivation});
     *         never {@code null}, never blank
     */
    String backendId();

    /**
     * Evaluate the budget remaining + burn rate for the supplied
     * {@link SloDefinition}.
     *
     * <p>Implementations read the upstream data, compute the
     * ratios, classify the outcome band per
     * {@link SloSnapshot#classifyBand(double)} on the happy path
     * (or pick the unknown / failure factory on the sad path),
     * and return the verdict. Implementations MUST NOT throw.</p>
     *
     * @param def the SLO definition to evaluate; never null
     *            (caller's responsibility -- enforced via the
     *            compact ctor on {@link SloDefinition})
     * @return the verdict; never {@code null}
     */
    SloSnapshot evaluate(SloDefinition def);
}
