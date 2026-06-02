package io.cortex.ingest.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the {@link OutboxPoller}
 * (P4.4b / ADR-0026).
 *
 * <p>Bound to the {@code cortex.ingest.outbox.poller} +
 * {@code cortex.ingest.outbox.cloudevent} property tree in
 * {@code application.yml}. Every value is env-overridable so prod
 * tuning is a redeploy-free operation.</p>
 *
 * @param poller     poller-cadence sub-tree
 * @param cloudevent CloudEvent-envelope sub-tree
 */
@ConfigurationProperties(prefix = "cortex.ingest.outbox")
public record OutboxPollerProperties(
        PollerProps poller,
        CloudEventProps cloudevent) {

    /**
     * Poller-cadence sub-tree. Wrapped so the YAML mirrors the
     * Java field grouping.
     *
     * @param enabled         master switch for the scheduled tick
     * @param fixedDelayMs    delay between poll cycles
     * @param batchSize       max rows fetched per cycle
     * @param backoffInitialMs first-failure backoff
     * @param backoffMaxMs     backoff ceiling
     */
    public record PollerProps(
            boolean enabled,
            long fixedDelayMs,
            int batchSize,
            long backoffInitialMs,
            long backoffMaxMs) {

        /**
         * Returns the next-attempt backoff for the supplied attempt
         * count using exponential doubling capped at
         * {@link #backoffMaxMs}.
         *
         * @param attempts number of prior publish attempts on the
         *                 row (1 after the first failure, 2 after
         *                 the second, etc.)
         * @return positive {@link Duration} bounded by
         *         {@code backoffMaxMs}
         */
        public Duration nextBackoff(final int attempts) {
            final long base = Math.max(this.backoffInitialMs, 1L);
            final int shift = Math.max(0, attempts - 1);
            final long doubled;
            if (shift >= Long.SIZE - 1) {
                doubled = Long.MAX_VALUE;
            } else {
                final long candidate = base << shift;
                doubled = candidate < 0L ? Long.MAX_VALUE : candidate;
            }
            final long capped = Math.min(doubled, Math.max(this.backoffMaxMs, base));
            return Duration.ofMillis(capped);
        }

        /**
         * Returns {@code true} when the uncapped exponential backoff
         * for {@code attempts} has reached or exceeded
         * {@link #backoffMaxMs}, i.e. the row has hit the retry
         * ceiling and the P4.4c DLQ branch (ADR-0027 D2) should
         * take over instead of rescheduling another retry.
         *
         * <p>Decision boundary lives here (rather than in
         * {@link OutboxPoller}) so the test surface can exercise the
         * math in isolation and the ADR-D2 contract is enforced by
         * the configuration root, not by the caller.</p>
         *
         * @param attempts attempt count for the failed publish (1
         *                 after the first failure, 2 after the
         *                 second, etc.)
         * @return {@code true} when the row should be DLQ'd; {@code
         *         false} when {@link #nextBackoff(int)} should be
         *         used to reschedule another retry
         */
        public boolean isRetryExhausted(final int attempts) {
            final long base = Math.max(this.backoffInitialMs, 1L);
            final long cap = Math.max(this.backoffMaxMs, base);
            final int shift = Math.max(0, attempts - 1);
            if (shift >= Long.SIZE - 1) {
                return true;
            }
            final long uncapped = base << shift;
            if (uncapped < 0L) {
                return true;
            }
            return uncapped >= cap;
        }
    }

    /**
     * CloudEvent-envelope sub-tree.
     *
     * @param source CE 1.0 envelope source URI
     * @param type   CE 1.0 envelope type identifier
     */
    public record CloudEventProps(String source, String type) {
    }
}
