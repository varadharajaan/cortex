package io.cortex.processor.sink;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the {@link ParsedEventSink} fan-out
 * (P5.3 / ADR-0030).
 *
 * <p>Bound to prefix {@code cortex.processor.sinks}. Each sink
 * sub-block carries its own {@code enabled} flag (read by the
 * {@code @ConditionalOnProperty} guard on the impl) plus a
 * {@code base-url} + per-call advisory timeout. Both sinks default
 * to {@code enabled=false} so a stock dev boot continues to run with
 * zero outbound HTTP traffic; opt-in lives in the smoke / prod
 * profile.</p>
 *
 * @param loki     Loki push API config block; never {@code null}
 *                 after construction (defensive defaults applied)
 * @param quickwit Quickwit ingest API config block; never
 *                 {@code null} after construction
 */
@ConfigurationProperties(prefix = "cortex.processor.sinks")
public record SinkProperties(Loki loki, Quickwit quickwit) {

    /** Default per-call advisory request timeout. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default Loki base URL (local-dev smoke compose). */
    public static final String DEFAULT_LOKI_BASE_URL = "http://localhost:3100";

    /** Default Quickwit base URL (local-dev smoke compose). */
    public static final String DEFAULT_QUICKWIT_BASE_URL = "http://localhost:7280";

    /** Default Quickwit index name written by the sink. */
    public static final String DEFAULT_QUICKWIT_INDEX = "cortex-logs";

    /**
     * Normalise nullable nested blocks to defaulted records so
     * downstream code never has to null-guard the sub-properties.
     *
     * @param loki     Loki sub-block (may be {@code null} from yml)
     * @param quickwit Quickwit sub-block (may be {@code null} from yml)
     */
    public SinkProperties {
        if (loki == null) {
            loki = new Loki(false, DEFAULT_LOKI_BASE_URL, DEFAULT_REQUEST_TIMEOUT);
        }
        if (quickwit == null) {
            quickwit = new Quickwit(false, DEFAULT_QUICKWIT_BASE_URL,
                    DEFAULT_QUICKWIT_INDEX, DEFAULT_REQUEST_TIMEOUT);
        }
    }

    /**
     * Loki push API sub-block.
     *
     * @param enabled        feature gate read by the
     *                       {@code @ConditionalOnProperty} on
     *                       {@code LokiSink}
     * @param baseUrl        base URL of the Loki gateway (no
     *                       trailing slash)
     * @param requestTimeout advisory per-call timeout enforced by
     *                       the JDK HTTP client
     */
    public record Loki(boolean enabled, String baseUrl, Duration requestTimeout) {

        /**
         * Defensive defaults for optional fields so a partially-filled
         * sub-block still wires.
         *
         * @param enabled        see record-level Javadoc
         * @param baseUrl        see record-level Javadoc
         * @param requestTimeout see record-level Javadoc
         */
        public Loki {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = DEFAULT_LOKI_BASE_URL;
            }
            if (requestTimeout == null) {
                requestTimeout = DEFAULT_REQUEST_TIMEOUT;
            }
        }
    }

    /**
     * Quickwit ingest API sub-block.
     *
     * @param enabled        feature gate read by the
     *                       {@code @ConditionalOnProperty} on
     *                       {@code QuickwitSink}
     * @param baseUrl        base URL of the Quickwit node (no
     *                       trailing slash)
     * @param index          target Quickwit index id (path segment
     *                       in the ingest URL)
     * @param requestTimeout advisory per-call timeout enforced by
     *                       the JDK HTTP client
     */
    public record Quickwit(boolean enabled, String baseUrl, String index,
                           Duration requestTimeout) {

        /**
         * Defensive defaults for optional fields so a partially-filled
         * sub-block still wires.
         *
         * @param enabled        see record-level Javadoc
         * @param baseUrl        see record-level Javadoc
         * @param index          see record-level Javadoc
         * @param requestTimeout see record-level Javadoc
         */
        public Quickwit {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = DEFAULT_QUICKWIT_BASE_URL;
            }
            if (index == null || index.isBlank()) {
                index = DEFAULT_QUICKWIT_INDEX;
            }
            if (requestTimeout == null) {
                requestTimeout = DEFAULT_REQUEST_TIMEOUT;
            }
        }
    }
}
