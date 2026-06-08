package io.cortex.monitoring.probe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled trigger that probes every configured target
 * service-id on each tick
 * (P8.2b / ADR-0046 Amendment 3 2026-06-08).
 *
 * <p>Gated by {@code cortex.monitoring.probe.enabled=true};
 * OFF by default so the scheduled task does not fire in
 * profiles that have not opted in. When enabled, the
 * {@code @Scheduled} task fires at the
 * {@link ProbeProperties#evaluationInterval()} cadence
 * (defaults to 30 s; matches the Prometheus scrape interval
 * used by infra/local) and calls
 * {@link ServiceHealthProbe#probe(ProbeRequest)} once per
 * service id declared in
 * {@link ProbeProperties#targets()}. The single autowired
 * {@link ServiceHealthProbe} bean (either the noop or the
 * eureka-actuator backend per the
 * {@code cortex.monitoring.probe.backend} binder gate) is the
 * consumer; this evaluator does NOT pick which backend wires,
 * it only orchestrates the per-tick fan-out.</p>
 *
 * <p>Mirror of {@link io.cortex.monitoring.slo.SloEvaluator}'s
 * shape: the cadence is read indirectly via the SpEL bean
 * reference {@code "#{@probeEvaluationIntervalMillis}"}
 * (resolved in
 * {@link ProbeSchedulerConfig#probeEvaluationIntervalMillis(ProbeProperties)})
 * rather than directly through a
 * {@code "${cortex.monitoring.probe.evaluation-interval}"}
 * placeholder, because Spring's
 * {@code ScheduledAnnotationBeanPostProcessor} resolves
 * {@code fixedRateString} via {@code Long.parseLong} with no
 * {@code Duration.parse} fallback in this Boot version --
 * operator-friendly values such as {@code 30s} or {@code 1h}
 * would fail bean creation with {@code NumberFormatException}
 * (LD141 standing rule, established by issue #120 / LD137).</p>
 *
 * <p>The evaluator is contract-defensive: a target whose probe
 * call throws (which would violate the SPI's "MUST NOT throw"
 * contract per ADR-0044 D1) is caught at this layer so one
 * rogue target cannot stall the scheduler loop -- the offending
 * target id is logged and skipped; the next target / next tick
 * continues. Empty {@code targets} list is a structural no-op
 * (does not throw, does not invoke the probe).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.monitoring.probe",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ScheduledProbeEvaluator {

    private static final Logger LOG =
            LoggerFactory.getLogger(ScheduledProbeEvaluator.class);

    private final ProbeProperties properties;
    private final ServiceHealthProbe probe;

    /**
     * Sole ctor. Spring injects the operator-declared properties
     * and the active probe bean (one bean per profile per the
     * {@code @ConditionalOnProperty} gates on
     * {@link io.cortex.monitoring.probe.NoopServiceHealthProbe}
     * and the eureka-actuator backend).
     *
     * @param probeProperties typed config bound from
     *                        {@code cortex.monitoring.probe.*}
     * @param serviceHealthProbe active probe bean (noop by
     *                           default; eureka-actuator when
     *                           {@code backend=eureka-actuator})
     */
    public ScheduledProbeEvaluator(
            final ProbeProperties probeProperties,
            final ServiceHealthProbe serviceHealthProbe) {
        this.properties = probeProperties;
        this.probe = serviceHealthProbe;
    }

    /**
     * Fire at the cadence declared by
     * {@code cortex.monitoring.probe.evaluation-interval}.
     *
     * <p>The cadence is read indirectly via the SpEL bean
     * reference {@code "#{@probeEvaluationIntervalMillis}"}
     * (resolved in
     * {@link ProbeSchedulerConfig#probeEvaluationIntervalMillis(ProbeProperties)})
     * per LD141 -- direct {@code "${...}"} placeholder usage on
     * {@code fixedRateString} fails bean creation with
     * {@code NumberFormatException} for any non-pure-millis
     * value such as {@code 30s} or {@code 1h}.</p>
     */
    @Scheduled(fixedRateString = "#{@probeEvaluationIntervalMillis}")
    public void evaluateAll() {
        evaluateOnce();
    }

    /**
     * Single fan-out pass: iterates every configured target
     * service-id and invokes the active probe with a
     * service-only {@link ProbeRequest}
     * ({@code instanceId=null}, which selects the first
     * registered instance per the
     * {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHealthProbe}
     * contract; the noop backend ignores the request). Exposed
     * for unit tests so we can exercise the loop without
     * standing up the Spring scheduler.
     */
    public void evaluateOnce() {
        if (this.properties.targets().isEmpty()) {
            return;
        }
        for (final String serviceId : this.properties.targets()) {
            tickOne(serviceId);
        }
    }

    /**
     * Probe one target. Guards the probe call with a try-catch
     * in case a future backend violates the SPI's "MUST NOT
     * throw" contract (defensive belt-and-braces -- the
     * contract is also structurally enforced by every adapter's
     * own try-catch in
     * {@link io.cortex.monitoring.probe.eureka.EurekaActuatorHealthProbe}).
     */
    private void tickOne(final String serviceId) {
        try {
            this.probe.probe(new ProbeRequest(serviceId, null));
        } catch (final RuntimeException ex) {
            LOG.warn("ServiceHealthProbe {} threw {} for serviceId={};"
                            + " skipping this target until the next tick",
                    this.probe.backendId(), ex.getClass().getSimpleName(),
                    serviceId);
        }
    }
}
