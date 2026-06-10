package io.cortex.monitoring.slo;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Immutable input to {@link SloBudgetEngine#evaluate(SloDefinition)}
 * (P8.2 / ADR-0046 D1).
 *
 * <p>Identifies the SLO target the engine should evaluate against
 * the current probe counter surface. The {@code serviceId} matches
 * the {@code service_id} tag emitted on
 * {@code cortex.monitoring.probe_total} counters by every
 * {@link io.cortex.monitoring.probe.ServiceHealthProbe} adapter;
 * the {@code sloName} is a short, free-form (but bounded by
 * configuration) label that distinguishes multiple SLOs targeting
 * the same service (e.g. {@code availability},
 * {@code success-ratio-99}).</p>
 *
 * <p>{@code targetSuccessRatio} is the operator-declared success
 * threshold, e.g. {@code 0.99} for "99% of probe attempts must
 * succeed". The complement
 * {@code (1 - targetSuccessRatio) = 0.01} is the allowed error
 * budget over the evaluation window.</p>
 *
 * <p>{@code window} is the evaluation window length and is
 * recorded for downstream operator context (alert annotations,
 * dashboard captions). The {@link MicrometerSloBudgetEngine}
 * derivation evaluates against the since-boot counter snapshot
 * regardless of the window value (operator's responsibility to
 * recycle the service or use Prometheus rate() in the dashboard
 * for true window-based rates); larger window values just signal
 * a "look at me over a longer time horizon" intent.</p>
 *
 * @param serviceId          Eureka service id the SLO targets;
 *                           must match the {@code service_id}
 *                           counter tag; never null/blank
 * @param sloName            short, bounded label distinguishing
 *                           this SLO from others on the same
 *                           service; never null/blank
 * @param targetSuccessRatio operator-declared success threshold in
 *                           {@code (0, 1)} exclusive (0.99 means
 *                           99% of probes must succeed); strictly
 *                           between 0 and 1
 * @param window             operator-declared evaluation window
 *                           (e.g. {@code PT1H}); never null,
 *                           positive
 * @param counterFamily      optional P8.3 counter-family source
 *                           used only by
 *                           {@link CounterFamilySloBudgetEngine};
 *                           null keeps the P8.2 availability
 *                           definition contract intact
 * @param timer              optional P8.4 timer histogram source
 * @param promQl             optional P8.5 PromQL source
 * @param composite          optional P8.6 composite source
 * @param otel               optional P8.7+ OTel span-metric source
 */
public record SloDefinition(String serviceId, String sloName,
                            double targetSuccessRatio, Duration window,
                            CounterFamilySource counterFamily,
                            TimerSource timer,
                            PromQlSource promQl,
                            CompositeSource composite,
                            OtelSource otel) {

    /** Lower bound (exclusive) for {@link #targetSuccessRatio}. */
    public static final double TARGET_MIN_EXCLUSIVE = 0.0d;

    /** Upper bound (exclusive) for {@link #targetSuccessRatio}. */
    public static final double TARGET_MAX_EXCLUSIVE = 1.0d;

    /**
     * Compact ctor enforces the field contract above. SPI
     * implementations may rely on every field being non-null /
     * non-blank and {@code targetSuccessRatio} being in the open
     * interval {@code (0, 1)}.
     */
    @ConstructorBinding
    public SloDefinition {
        if (StringUtils.isBlank(serviceId)) {
            throw new IllegalArgumentException(
                    "serviceId must not be null or blank");
        }
        if (StringUtils.isBlank(sloName)) {
            throw new IllegalArgumentException(
                    "sloName must not be null or blank");
        }
        if (!(targetSuccessRatio > TARGET_MIN_EXCLUSIVE
                && targetSuccessRatio < TARGET_MAX_EXCLUSIVE)) {
            throw new IllegalArgumentException(
                    "targetSuccessRatio must be in the open interval (0, 1)"
                            + " but was " + targetSuccessRatio);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "window must be a positive Duration");
        }
    }

    /**
     * Backwards-compatible P8.2/P8.3 constructor for availability
     * and counter-family definitions. New source-specific backends
     * use the canonical record constructor.
     *
     * @param serviceId          Eureka service id
     * @param sloName            SLO label
     * @param targetSuccessRatio success target in {@code (0, 1)}
     * @param window             positive evaluation window
     * @param counterFamily      optional P8.3 source
     */
    public SloDefinition(final String serviceId, final String sloName,
                         final double targetSuccessRatio,
                         final Duration window,
                         final CounterFamilySource counterFamily) {
        this(serviceId, sloName, targetSuccessRatio, window,
                counterFamily, null, null, null, null);
    }

    /**
     * Backwards-compatible P8.2 constructor for plain availability
     * definitions.
     *
     * @param serviceId          Eureka service id
     * @param sloName            SLO label
     * @param targetSuccessRatio success target in {@code (0, 1)}
     * @param window             positive evaluation window
     */
    public SloDefinition(final String serviceId, final String sloName,
                         final double targetSuccessRatio,
                         final Duration window) {
        this(serviceId, sloName, targetSuccessRatio, window,
                null, null, null, null, null);
    }

    /**
     * P8.3 counter-family source declaration. The backend uses
     * {@link #metricName()} to select one Prometheus counter
     * family from the target service's actuator exposition, applies
     * {@link #requiredTags()} as an optional static filter, then
     * classifies samples matching {@link #successTagPredicate()} as
     * successes and samples matching {@link #failureTagPredicate()}
     * as failures.
     *
     * @param metricName          Micrometer or Prometheus counter
     *                            family name, e.g.
     *                            {@code cortex.remediation.dispatched_total}
     *                            or
     *                            {@code cortex_remediation_dispatched_total}
     * @param successTagPredicate tag predicate identifying success
     *                            samples inside the family
     * @param failureTagPredicate tag predicate identifying failure
     *                            samples inside the family
     * @param requiredTags        optional exact-match tag filters
     *                            applied before success/failure
     *                            classification; never null after
     *                            construction
     */
    public record CounterFamilySource(
            String metricName,
            TagPredicate successTagPredicate,
            TagPredicate failureTagPredicate,
            Map<String, String> requiredTags) {

        /**
         * Validate and defensively copy the counter-family source
         * declaration.
         */
        public CounterFamilySource {
            if (StringUtils.isBlank(metricName)) {
                throw new IllegalArgumentException(
                        "counterFamily.metricName must not be null or blank");
            }
            if (successTagPredicate == null) {
                throw new IllegalArgumentException(
                        "counterFamily.successTagPredicate must not be null");
            }
            if (failureTagPredicate == null) {
                throw new IllegalArgumentException(
                        "counterFamily.failureTagPredicate must not be null");
            }
            requiredTags = copyTags(requiredTags, "counterFamily.requiredTags");
        }
    }

    /**
     * P8.4 timer histogram source. The backend expects a Prometheus
     * histogram bucket family and treats the bucket at or below
     * {@link #threshold()} as successes. The {@code +Inf} bucket is
     * the total request count.
     *
     * @param metricName   timer histogram base name without the
     *                     {@code _bucket} suffix
     * @param threshold    latency threshold that a request must stay
     *                     under to count as a success
     * @param requiredTags optional exact-match tag filters
     */
    public record TimerSource(String metricName, Duration threshold,
                              Map<String, String> requiredTags) {

        /**
         * Validate and defensively copy the timer source declaration.
         */
        public TimerSource {
            if (StringUtils.isBlank(metricName)) {
                throw new IllegalArgumentException(
                        "timer.metricName must not be null or blank");
            }
            if (threshold == null || threshold.isZero()
                    || threshold.isNegative()) {
                throw new IllegalArgumentException(
                        "timer.threshold must be a positive Duration");
            }
            requiredTags = copyTags(requiredTags, "timer.requiredTags");
        }
    }

    /**
     * P8.5 PromQL source. The backend executes both instant queries
     * against Prometheus and treats their scalar/vector sums as
     * success and failure counts.
     *
     * @param successQuery PromQL expression returning success count
     * @param failureQuery PromQL expression returning failure count
     */
    public record PromQlSource(String successQuery, String failureQuery) {

        /**
         * Validate the PromQL source declaration.
         */
        public PromQlSource {
            if (StringUtils.isBlank(successQuery)) {
                throw new IllegalArgumentException(
                        "promQl.successQuery must not be null or blank");
            }
            if (StringUtils.isBlank(failureQuery)) {
                throw new IllegalArgumentException(
                        "promQl.failureQuery must not be null or blank");
            }
        }
    }

    /**
     * P8.6 composite source. The backend reads previously recorded
     * child snapshots from {@link SloSnapshotStore} and combines
     * them either by worst remaining budget or weighted average.
     *
     * @param mode       one of {@code worst-of} or
     *                   {@code weighted-average}
     * @param components child SLO references to combine
     */
    public record CompositeSource(String mode, List<ComponentRef> components) {

        /** Worst child budget wins. */
        public static final String MODE_WORST_OF = "worst-of";

        /** Weighted average across child budgets. */
        public static final String MODE_WEIGHTED_AVERAGE = "weighted-average";

        /**
         * Validate and defensively copy the composite declaration.
         */
        public CompositeSource {
            if (StringUtils.isBlank(mode)) {
                mode = MODE_WORST_OF;
            }
            if (!MODE_WORST_OF.equals(mode)
                    && !MODE_WEIGHTED_AVERAGE.equals(mode)) {
                throw new IllegalArgumentException(
                        "composite.mode must be worst-of or weighted-average");
            }
            if (components == null || components.isEmpty()) {
                throw new IllegalArgumentException(
                        "composite.components must not be null or empty");
            }
            components = List.copyOf(components);
        }
    }

    /**
     * Child SLO reference for the P8.6 composite backend.
     *
     * @param serviceId referenced snapshot service id
     * @param sloName   referenced snapshot SLO name
     * @param weight    positive weight used only by weighted average
     */
    public record ComponentRef(String serviceId, String sloName,
                               double weight) {

        /**
         * Validate one composite component reference.
         */
        public ComponentRef {
            if (StringUtils.isBlank(serviceId)) {
                throw new IllegalArgumentException(
                        "component.serviceId must not be null or blank");
            }
            if (StringUtils.isBlank(sloName)) {
                throw new IllegalArgumentException(
                        "component.sloName must not be null or blank");
            }
            if (!(weight > 0.0d)) {
                throw new IllegalArgumentException(
                        "component.weight must be positive");
            }
        }
    }

    /**
     * P8.7+ OTel span-metric ratio source. The backend uses the
     * same Prometheus counter-family parsing mechanics as P8.3,
     * but keeps a distinct backend id so operators can separate
     * span-derived SLOs from service-emitted counters.
     *
     * @param metricName          OTel span metric counter family
     * @param successTagPredicate tag predicate identifying success
     *                            spans
     * @param failureTagPredicate tag predicate identifying failure
     *                            spans
     * @param requiredTags        optional exact-match tag filters
     */
    public record OtelSource(
            String metricName,
            TagPredicate successTagPredicate,
            TagPredicate failureTagPredicate,
            Map<String, String> requiredTags) {

        /**
         * Validate and defensively copy the OTel source declaration.
         */
        public OtelSource {
            if (StringUtils.isBlank(metricName)) {
                throw new IllegalArgumentException(
                        "otel.metricName must not be null or blank");
            }
            if (successTagPredicate == null) {
                throw new IllegalArgumentException(
                        "otel.successTagPredicate must not be null");
            }
            if (failureTagPredicate == null) {
                throw new IllegalArgumentException(
                        "otel.failureTagPredicate must not be null");
            }
            requiredTags = copyTags(requiredTags, "otel.requiredTags");
        }
    }

    /**
     * Exact-match tag predicate used by the P8.3 counter-family
     * backend. A sample matches when the declared tag key exists
     * and its value is one of the configured values.
     *
     * @param tagName   Prometheus tag key to inspect
     * @param tagValues bounded allowlist of accepted values
     */
    public record TagPredicate(String tagName, List<String> tagValues) {

        /**
         * Validate and defensively copy the tag predicate.
         */
        public TagPredicate {
            if (StringUtils.isBlank(tagName)) {
                throw new IllegalArgumentException(
                        "tagPredicate.tagName must not be null or blank");
            }
            tagValues = copyValues(tagValues, "tagPredicate.tagValues");
        }

        /**
         * Determine whether this predicate matches a parsed sample
         * tag map.
         *
         * @param tags sample tags parsed from the Prometheus text
         *             exposition; may be null
         * @return true when {@link #tagName()} exists and its value
         *         is present in {@link #tagValues()}
         */
        public boolean matches(final Map<String, String> tags) {
            if (tags == null || tags.isEmpty()) {
                return false;
            }
            return this.tagValues.contains(tags.get(this.tagName));
        }
    }

    private static List<String> copyValues(final List<String> values,
                                           final String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    fieldName + " must not be null or empty");
        }
        for (final String value : values) {
            if (StringUtils.isBlank(value)) {
                throw new IllegalArgumentException(
                        fieldName + " must not contain blank values");
            }
        }
        return List.copyOf(values);
    }

    private static Map<String, String> copyTags(
            final Map<String, String> tags, final String fieldName) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        for (final Map.Entry<String, String> entry : tags.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())
                    || StringUtils.isBlank(entry.getValue())) {
                throw new IllegalArgumentException(
                        fieldName + " must not contain blank keys or values");
            }
        }
        return Map.copyOf(tags);
    }
}
