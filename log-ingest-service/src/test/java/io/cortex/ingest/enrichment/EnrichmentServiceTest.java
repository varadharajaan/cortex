package io.cortex.ingest.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.agent.LogEntry;
import io.cortex.agent.LogLevel;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link EnrichmentService} orchestrator
 * (P4.3 / plan.md row 169). Confirms the four documented
 * inputs combine in the documented order with server-owned
 * keys overwriting any client-supplied entry that normalises
 * to the same canonical key (ADR-0024).
 */
class EnrichmentServiceTest {

    /** Fixed tenant id used by all assertions in this suite. */
    private static final String TENANT = "cortex-dev";

    /** Fixed correlation id used by all assertions in this suite. */
    private static final String CORRELATION = "corr-1";

    /** Subject under test. */
    private EnrichmentService service;

    /** Wires the geo stub to its default-substituting properties. */
    @BeforeEach
    void initService() {
        this.service = new EnrichmentService(
                new GeoEnricher(new EnrichmentProperties(null)));
    }

    /** Tenant, trace, and geo labels are always present after enrichment. */
    @Test
    void enrichStampsTenantTraceAndGeoLabels() {
        final LogEntry entry = sample(Map.of("env", "prod"));

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out)
                .containsEntry("env", "prod")
                .containsEntry(LogEntry.LABEL_TENANT, TENANT)
                .containsEntry(LogEntry.LABEL_TRACE_ID, CORRELATION)
                .containsEntry(GeoEnricher.LABEL_GEO_COUNTRY,
                        EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** Client-supplied {@code tenant} label cannot spoof the resolved tenant. */
    @Test
    void clientSuppliedTenantLabelIsOverwrittenByServerTenant() {
        final LogEntry entry = sample(Map.of(LogEntry.LABEL_TENANT, "attacker"));

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out).containsEntry(LogEntry.LABEL_TENANT, TENANT);
    }

    /** Client-supplied {@code trace_id} label cannot spoof the minted trace id. */
    @Test
    void clientSuppliedTraceIdLabelIsOverwrittenByServerTraceId() {
        final LogEntry entry = sample(Map.of(LogEntry.LABEL_TRACE_ID, "fake-trace"));

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out).containsEntry(LogEntry.LABEL_TRACE_ID, CORRELATION);
    }

    /** Client-supplied {@code geo_country} is overwritten by the resolved value. */
    @Test
    void clientSuppliedGeoCountryLabelIsOverwrittenByServerGeo() {
        final LogEntry entry = sample(Map.of(GeoEnricher.LABEL_GEO_COUNTRY, "spoofed"));

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out).containsEntry(GeoEnricher.LABEL_GEO_COUNTRY,
                EnrichmentProperties.DEFAULT_FIXED_COUNTRY);
    }

    /** Normalisation happens before stamping (mixed-case client key collapses). */
    @Test
    void clientLabelsAreNormalisedBeforeStamping() {
        final Map<String, String> raw = new LinkedHashMap<>();
        raw.put("Env", "prod");
        raw.put("  Region  ", "  eu-west  ");
        final LogEntry entry = sample(raw);

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out)
                .containsEntry("env", "prod")
                .containsEntry("region", "eu-west")
                .doesNotContainKey("Env")
                .doesNotContainKey("  Region  ");
    }

    /** An empty inbound labels map still produces tenant + trace + geo. */
    @Test
    void emptyInboundLabelsStillProducesServerLabels() {
        final LogEntry entry = sample(Map.of());

        final Map<String, String> out =
                this.service.enrich(entry, TENANT, CORRELATION);

        assertThat(out).containsOnlyKeys(
                LogEntry.LABEL_TENANT,
                LogEntry.LABEL_TRACE_ID,
                GeoEnricher.LABEL_GEO_COUNTRY);
    }

    /**
     * Builds a minimal {@link LogEntry} for these tests.
     *
     * @param labels labels map; passed through to the canonical constructor
     * @return non-null {@link LogEntry}
     */
    private static LogEntry sample(final Map<String, String> labels) {
        return new LogEntry(
                Instant.parse("2026-05-31T12:00:00Z"),
                LogLevel.INFO,
                "test-svc",
                "hello",
                labels);
    }
}
