package io.cortex.indexer.admin.quickwit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.indexer.admin.CardinalityBudget;
import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.IndexSpec;
import io.cortex.indexer.admin.RetentionPolicy;
import io.cortex.indexer.metrics.IndexerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link QuickwitHttpAdmin} -- backend id,
 * null-spec guard rails, {@code renderCreateBody} body shape,
 * and the P7.2 {@code renderRetentionBody} + {@code applyRetention}
 * guard rails (P7.1 / ADR-0039 D6 + P7.2 / ADR-0040 D5).
 *
 * <p>The HTTP outcome table is exercised end-to-end by
 * {@link QuickwitHttpAdminWireMockIT} against a real
 * {@link RestClient} talking to an in-process WireMock; this
 * unit class focuses on the helpers + guard rails that don't
 * need wire traffic.</p>
 */
@DisplayName("QuickwitHttpAdmin")
class QuickwitHttpAdminTest {

    /** Sample IndexSpec reused across body-shape tests. */
    private static final IndexSpec SAMPLE_SPEC =
            new IndexSpec("tenant-A", "cortex-tenantA-v1", "v1");

    /** Fixed clock pinned to 2026-06-07T00:00:00Z for retention tests. */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper();
    private final IndexerMetrics metrics = new IndexerMetrics(
            new SimpleMeterRegistry(), List.of(), List.of());
    private final QuickwitProperties properties = new QuickwitProperties(
            QuickwitProperties.DEFAULT_BASE_URL,
            QuickwitProperties.DEFAULT_REQUEST_TIMEOUT,
            QuickwitProperties.DEFAULT_DOC_MAPPING_VERSION);
    private final RestClient restClient = RestClient.builder().build();

    private final QuickwitHttpAdmin admin = new QuickwitHttpAdmin(
            this.properties, this.restClient, this.metrics, this.mapper,
            FIXED_CLOCK);

    @Test
    void backendIdReportsQuickwit() {
        assertThat(this.admin.backendId())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void propertiesAccessorReturnsBoundConfig() {
        assertThat(this.admin.properties()).isSameAs(this.properties);
    }

    @Test
    void nullSpecOnEnsureIndexReturnsPermanentFailure() {
        final IndexAdminResult result = this.admin.ensureIndex(null);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-spec");
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void nullIndexIdOnDropIndexReturnsPermanentFailure() {
        final IndexAdminResult result = this.admin.dropIndex(null);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-index-id");
    }

    @Test
    void blankIndexIdOnDropIndexReturnsPermanentFailure() {
        final IndexAdminResult result = this.admin.dropIndex("  ");
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-index-id");
    }

    @Test
    void nullSpecOnApplyRetentionReturnsPermanentFailure() {
        final IndexAdminResult result = this.admin.applyRetention(
                null, new RetentionPolicy(Duration.ofDays(7)));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-spec");
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void nullPolicyOnApplyRetentionReturnsPermanentFailure() {
        final IndexAdminResult result =
                this.admin.applyRetention(SAMPLE_SPEC, null);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-policy");
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void nullSpecOnEnsureIndexWithBudgetReturnsPermanentFailure() {
        final IndexAdminResult result = this.admin.ensureIndex(
                null, new CardinalityBudget(5));
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-spec");
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void nullBudgetOnEnsureIndexWithBudgetReturnsPermanentFailure() {
        final IndexAdminResult result =
                this.admin.ensureIndex(SAMPLE_SPEC, null);
        assertThat(result.outcome())
                .isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:null-budget");
        assertThat(result.backend())
                .isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    @Test
    void renderRetentionBodyContainsQueryWildcardAndEndTimestamp() {
        final long cutoff = 1_717_700_000L;
        final Map<String, Object> body = this.admin.renderRetentionBody(cutoff);
        assertThat(body.get("query")).isEqualTo("*");
        assertThat(body.get("end_timestamp")).isEqualTo(cutoff);
    }

    @Test
    void renderRetentionBodyOmitsStartTimestamp() {
        final Map<String, Object> body =
                this.admin.renderRetentionBody(1L);
        // Unbounded past: Quickwit DeleteQuery treats omitted
        // start_timestamp as -infinity.
        assertThat(body).doesNotContainKey("start_timestamp");
    }

    @Test
    void renderCreateBodyStampsTheConfigVersion() {
        final Map<String, Object> body = this.admin.renderCreateBody(SAMPLE_SPEC);
        assertThat(body.get("version"))
                .isEqualTo(QuickwitHttpAdmin.QUICKWIT_INDEX_CONFIG_VERSION);
    }

    @Test
    void renderCreateBodyEchoesTheSpecIndexId() {
        final Map<String, Object> body = this.admin.renderCreateBody(SAMPLE_SPEC);
        assertThat(body.get("index_id")).isEqualTo(SAMPLE_SPEC.indexId());
    }

    @SuppressWarnings("unchecked")
    @Test
    void renderCreateBodyCarriesTheFullFieldMappingSet() {
        final Map<String, Object> body = this.admin.renderCreateBody(SAMPLE_SPEC);
        final Map<String, Object> docMapping =
                (Map<String, Object>) body.get("doc_mapping");
        assertThat(docMapping.get("timestamp_field")).isEqualTo("ts");
        final List<Map<String, Object>> fields =
                (List<Map<String, Object>>) docMapping.get("field_mappings");
        // Mirror of P5.3 QuickwitSink.renderDoc field set.
        assertThat(fields).hasSize(10);
        assertThat(fields).extracting(f -> f.get("name"))
                .containsExactly("id", "tenant_id", "event_id", "ts",
                        "level", "service", "message", "anomaly",
                        "severity", "reason");
    }

    @SuppressWarnings("unchecked")
    @Test
    void renderCreateBodyCarriesDefaultSearchFields() {
        final Map<String, Object> body = this.admin.renderCreateBody(SAMPLE_SPEC);
        final Map<String, Object> searchSettings =
                (Map<String, Object>) body.get("search_settings");
        final List<String> defaultSearch =
                (List<String>) searchSettings.get("default_search_fields");
        assertThat(defaultSearch)
                .containsExactly("message", "service", "reason");
    }
}
