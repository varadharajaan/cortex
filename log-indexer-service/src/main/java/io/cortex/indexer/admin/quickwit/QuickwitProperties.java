package io.cortex.indexer.admin.quickwit;

import java.time.Duration;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for {@link QuickwitHttpAdmin}
 * (P7.1 / ADR-0039 D2).
 *
 * <p>Bound to prefix {@code cortex.indexer.quickwit}. The
 * {@code base-url} is the root of the Quickwit REST API
 * (typically {@code http://localhost:7280} for dev and the
 * cluster ingress in prod). {@code request-timeout} controls
 * both the connect + read timeouts on the underlying JDK HTTP
 * client per LD42 HTTP/1.1 pin + LD121 dual-timeout. The
 * {@code doc-mapping-version} is appended to index ids so
 * forward-incompatible schema bumps land as a fresh index
 * rather than mutating an existing one.</p>
 *
 * @param baseUrl            Quickwit REST API root URL; blank
 *                           coerces to {@link #DEFAULT_BASE_URL}
 *                           so a partially-filled yml still wires
 * @param requestTimeout     per-call advisory timeout for connect +
 *                           read (default 5 s); enforced by the JDK
 *                           HTTP client; on expiry the adapter
 *                           returns a transient-failure outcome with
 *                           {@code reason=quickwit:timeout}
 * @param docMappingVersion  trailing version segment that the
 *                           {@link io.cortex.indexer.admin.IndexSpec}
 *                           builder appends to the tenant-scoped
 *                           index id; null/blank coerces to
 *                           {@link #DEFAULT_DOC_MAPPING_VERSION}
 */
@Validated
@ConfigurationProperties(prefix = "cortex.indexer.quickwit")
public record QuickwitProperties(String baseUrl,
                                 Duration requestTimeout,
                                 String docMappingVersion) {

    /** Default Quickwit REST API root URL (dev / Docker Compose). */
    public static final String DEFAULT_BASE_URL = "http://localhost:7280";

    /** Default per-call advisory request timeout (LD42 HTTP/1.1 pin). */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Default doc-mapping version segment appended to index ids. */
    public static final String DEFAULT_DOC_MAPPING_VERSION = "v1";

    /**
     * Defensive defaults so a single missing env-var doesn't
     * crash the boot -- the noop backend is still the production
     * default, so the only consumers of this class are tests +
     * dev-mode boots that opted into {@code backend=quickwit}.
     */
    public QuickwitProperties {
        baseUrl = StringUtils.isBlank(baseUrl) ? DEFAULT_BASE_URL : baseUrl;
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        docMappingVersion = StringUtils.isBlank(docMappingVersion)
                ? DEFAULT_DOC_MAPPING_VERSION
                : docMappingVersion;
    }
}
