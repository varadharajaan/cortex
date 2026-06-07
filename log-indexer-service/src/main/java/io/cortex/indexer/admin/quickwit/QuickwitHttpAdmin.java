package io.cortex.indexer.admin.quickwit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.IndexSpec;
import io.cortex.indexer.admin.QuickwitIndexAdmin;
import io.cortex.indexer.admin.RetentionPolicy;
import io.cortex.indexer.constants.IndexerHttp;
import io.cortex.indexer.metrics.IndexerMetrics;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real {@link QuickwitIndexAdmin} implementation backed by the
 * Quickwit REST admin surface ({@code /api/v1/indexes}, P7.1 /
 * ADR-0039).
 *
 * <p>Activated only when {@code cortex.indexer.admin.backend=quickwit}.
 * The default-dev profile leaves the property unset, which means
 * the noop adapter's {@code matchIfMissing=true} wins and this
 * bean stays out of the context. The two adapters are mutually
 * exclusive at the {@code @ConditionalOnProperty} level.</p>
 *
 * <p><strong>HTTP flow</strong> (ADR-0039 D4 / D5):</p>
 * <ul>
 *   <li>{@code ensureIndex(IndexSpec)} -- {@code GET
 *       /api/v1/indexes/&lt;indexId&gt;} first; 2xx returns
 *       {@code exists} without a write. 404 falls through to
 *       {@code POST /api/v1/indexes} with the Quickwit
 *       {@code IndexConfig} JSON body; 2xx returns {@code created}.
 *       All other statuses delegate to
 *       {@link RestAdminTemplate} for classification.</li>
 *   <li>{@code dropIndex(String)} -- {@code DELETE
 *       /api/v1/indexes/&lt;indexId&gt;}; 2xx and 404 both return
 *       {@code dropped} per SPI idempotence contract (ADR-0038
 *       D5). All other statuses delegate to
 *       {@link RestAdminTemplate}.</li>
 *   <li>{@code applyRetention(IndexSpec, RetentionPolicy)} --
 *       {@code POST /api/v1/&lt;indexId&gt;/delete-tasks} with the
 *       Quickwit {@code DeleteQuery} body
 *       {@code {"query":"*","end_timestamp":<epoch_seconds>}};
 *       cutoff computed as {@code clock.instant().minus(ttl)
 *       .getEpochSecond()}. 2xx returns {@code retention_applied}.
 *       404 (index missing) is a config error and surfaces as
 *       {@code permanent_failure:quickwit:4xx:404} via the
 *       template (P7.2 / ADR-0040 D4).</li>
 * </ul>
 *
 * <p>Every call ends with a
 * {@link IndexerMetrics#incIndexAdmin(String, String, String)}
 * tick so the Micrometer counter family the P7.0 bootstrap loop
 * pre-registered is kept current. {@code RuntimeException}s are
 * caught and downgraded to {@code transient_failure} per the SPI
 * contract that admins MUST NOT throw.</p>
 *
 * <p>Outbound HTTP is pinned to HTTP/1.1 via the
 * {@link RestClient} bean published by {@link QuickwitHttpConfig}
 * (LD42 + LD121).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "cortex.indexer.admin",
        name = "backend",
        havingValue = "quickwit")
public final class QuickwitHttpAdmin implements QuickwitIndexAdmin {

    private static final Logger LOG = LoggerFactory.getLogger(QuickwitHttpAdmin.class);

    /** Quickwit admin root path (cluster create + list). */
    static final String INDEXES_PATH = "/api/v1/indexes";

    /** Per-index Quickwit admin path template; consumes one URI variable. */
    static final String INDEX_PATH = "/api/v1/indexes/{indexId}";

    /**
     * Quickwit Delete API path template (P7.2 / ADR-0040 D4);
     * consumes one URI variable. The {@code DeleteQuery} body
     * carries the {@code end_timestamp} cutoff.
     */
    static final String DELETE_TASKS_PATH = "/api/v1/{indexId}/delete-tasks";

    /** Quickwit IndexConfig schema version the client targets. */
    static final String QUICKWIT_INDEX_CONFIG_VERSION = "0.7";

    private final QuickwitProperties properties;
    private final RestClient restClient;
    private final IndexerMetrics metrics;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestAdminTemplate template =
            new RestAdminTemplate(IndexAdminResult.BACKEND_QUICKWIT);

    /**
     * Spring constructor. Delegates to the test-seam ctor with the
     * system UTC clock.
     *
     * @param properties bound configuration block
     * @param restClient the {@link QuickwitHttpConfig#quickwitAdminRestClient
     *                   quickwitAdminRestClient} bean (HTTP/1.1 + dual timeout)
     * @param metrics    shared indexer metrics registry
     * @param mapper     shared Jackson mapper (autoconfigured by Spring Boot)
     */
    @Autowired 
    public QuickwitHttpAdmin(final QuickwitProperties properties,
                                        final RestClient restClient,
                                        final IndexerMetrics metrics,
                                        final ObjectMapper mapper) {
        this(properties, restClient, metrics, mapper, Clock.systemUTC());
    }

    /**
     * Test-seam constructor (P7.2 / ADR-0040 D5). The {@code clock}
     * is used to compute the {@code end_timestamp} cutoff in
     * {@link #applyRetention(IndexSpec, RetentionPolicy)} so unit
     * tests can pin the value with {@link Clock#fixed}. Package
     * private so production wiring goes through the {@code @Autowired}
     * ctor above.
     *
     * @param properties bound configuration block
     * @param restClient the {@link QuickwitHttpConfig#quickwitAdminRestClient
     *                   quickwitAdminRestClient} bean (HTTP/1.1 + dual timeout)
     * @param metrics    shared indexer metrics registry
     * @param mapper     shared Jackson mapper (autoconfigured by Spring Boot)
     * @param clock      time source for retention cutoff computation
     */
    QuickwitHttpAdmin(final QuickwitProperties properties,
                      final RestClient restClient,
                      final IndexerMetrics metrics,
                      final ObjectMapper mapper,
                      final Clock clock) {
        this.properties = properties;
        this.restClient = restClient;
        this.metrics = metrics;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String backendId() {
        return IndexAdminResult.BACKEND_QUICKWIT;
    }

    @Override
    public IndexAdminResult ensureIndex(final IndexSpec spec) {
        if (spec == null) {
            final IndexAdminResult result = IndexAdminResult.permanentFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:null-spec");
            tick(result, null);
            return result;
        }

        // Step 1: GET to check existence. 200 -> exists; 404 -> create.
        final IndexAdminResult existsCheck = checkExists(spec);
        if (existsCheck != null) {
            tick(existsCheck, spec.tenantId());
            return existsCheck;
        }

        // Step 2: POST to create. Body is rendered from the IndexSpec
        // doc-mapping version + the static schema definition.
        final IndexAdminResult createResult = createIndex(spec);
        tick(createResult, spec.tenantId());
        return createResult;
    }

    @Override
    public IndexAdminResult dropIndex(final String indexId) {
        if (indexId == null || indexId.isBlank()) {
            final IndexAdminResult result = IndexAdminResult.permanentFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:null-index-id");
            tick(result, null);
            return result;
        }

        try {
            this.restClient.delete()
                    .uri(INDEX_PATH, indexId)
                    .retrieve()
                    .toBodilessEntity();
            final IndexAdminResult result =
                    IndexAdminResult.dropped(IndexAdminResult.BACKEND_QUICKWIT);
            tick(result, null);
            return result;
        } catch (final RestClientResponseException ex) {
            if (ex.getStatusCode().value() == IndexerHttp.NOT_FOUND) {
                // Idempotence: dropping a missing index is success.
                LOG.debug("quickwit dropIndex 404 (already absent) indexId={}",
                        indexId);
                final IndexAdminResult result = IndexAdminResult.dropped(
                        IndexAdminResult.BACKEND_QUICKWIT);
                tick(result, null);
                return result;
            }
            LOG.warn("quickwit dropIndex non-2xx indexId={} status={}: {}",
                    indexId, ex.getStatusCode().value(), ex.getMessage());
            final IndexAdminResult result = this.template.classifyHttp(ex);
            tick(result, null);
            return result;
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit dropIndex transport failure indexId={}: {}",
                    indexId, ex.getMessage());
            final IndexAdminResult result = this.template.classifyTransport(ex);
            tick(result, null);
            return result;
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit dropIndex unexpected failure indexId={}: {}",
                    indexId, ex.getMessage());
            final IndexAdminResult result = this.template.classifyUnknown(ex);
            tick(result, null);
            return result;
        }
    }

    /**
     * Issue the GET probe for the index. Returns a terminal
     * {@link IndexAdminResult} for any outcome that is NOT
     * "go-create" (404). Returns {@code null} only when the caller
     * should proceed to the create step.
     *
     * @param spec the index spec
     * @return terminal {@link IndexAdminResult} or {@code null} on 404
     */
    private IndexAdminResult checkExists(final IndexSpec spec) {
        try {
            this.restClient.get()
                    .uri(INDEX_PATH, spec.indexId())
                    .retrieve()
                    .toBodilessEntity();
            return IndexAdminResult.exists(IndexAdminResult.BACKEND_QUICKWIT);
        } catch (final RestClientResponseException ex) {
            if (ex.getStatusCode().value() == IndexerHttp.NOT_FOUND) {
                LOG.debug("quickwit ensureIndex GET 404 (will create) "
                        + "tenantId={} indexId={}",
                        spec.tenantId(), spec.indexId());
                return null;
            }
            LOG.warn("quickwit ensureIndex GET non-2xx tenantId={} "
                    + "indexId={} status={}: {}",
                    spec.tenantId(), spec.indexId(),
                    ex.getStatusCode().value(), ex.getMessage());
            return this.template.classifyHttp(ex);
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit ensureIndex GET transport failure tenantId={} "
                    + "indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            return this.template.classifyTransport(ex);
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit ensureIndex GET unexpected failure tenantId={} "
                    + "indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            return this.template.classifyUnknown(ex);
        }
    }

    /**
     * Issue the POST create call for the index.
     *
     * @param spec the index spec
     * @return terminal {@link IndexAdminResult}
     */
    private IndexAdminResult createIndex(final IndexSpec spec) {
        final String body;
        try {
            body = this.mapper.writeValueAsString(renderCreateBody(spec));
        } catch (final JsonProcessingException ex) {
            LOG.warn("quickwit ensureIndex body serialisation failed "
                    + "tenantId={} indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            return IndexAdminResult.transientFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:unknown");
        }

        try {
            this.restClient.post()
                    .uri(INDEXES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return IndexAdminResult.created(IndexAdminResult.BACKEND_QUICKWIT);
        } catch (final RestClientResponseException ex) {
            LOG.warn("quickwit ensureIndex POST non-2xx tenantId={} "
                    + "indexId={} status={}: {}",
                    spec.tenantId(), spec.indexId(),
                    ex.getStatusCode().value(), ex.getMessage());
            return this.template.classifyHttp(ex);
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit ensureIndex POST transport failure tenantId={} "
                    + "indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            return this.template.classifyTransport(ex);
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit ensureIndex POST unexpected failure tenantId={} "
                    + "indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            return this.template.classifyUnknown(ex);
        }
    }

    /**
     * Render the minimal Quickwit {@code IndexConfig} JSON body
     * for create. Field set mirrors the P5.3 {@code QuickwitSink}
     * writer-side document shape (id + tenant_id + event_id + ts
     * + level + service + message + anomaly + severity + reason)
     * so the doc-mapping the indexer creates matches what the
     * processor actually writes. Bound to schema version
     * {@value #QUICKWIT_INDEX_CONFIG_VERSION}.
     *
     * @param spec the index spec
     * @return ordered map suitable for Jackson encoding
     */
    Map<String, Object> renderCreateBody(final IndexSpec spec) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", QUICKWIT_INDEX_CONFIG_VERSION);
        body.put("index_id", spec.indexId());

        final Map<String, Object> docMapping = new LinkedHashMap<>();
        docMapping.put("field_mappings", buildFieldMappings());
        docMapping.put("timestamp_field", "ts");
        body.put("doc_mapping", docMapping);

        final Map<String, Object> searchSettings = new LinkedHashMap<>();
        searchSettings.put("default_search_fields",
                List.of("message", "service", "reason"));
        body.put("search_settings", searchSettings);

        return body;
    }

    /**
     * Build the Quickwit field-mapping list. Same field set as the
     * P5.3 {@code QuickwitSink.renderDoc} writer.
     *
     * @return ordered list of Quickwit field-mapping objects
     */
    private static List<Map<String, Object>> buildFieldMappings() {
        final Map<String, Object> ts = new LinkedHashMap<>();
        ts.put("name", "ts");
        ts.put("type", "datetime");
        ts.put("fast", true);
        ts.put("input_formats", List.of("rfc3339"));
        ts.put("output_format", "rfc3339");
        return List.of(
                Map.of("name", "id", "type", "text", "tokenizer", "raw"),
                Map.of("name", "tenant_id", "type", "text", "tokenizer", "raw"),
                Map.of("name", "event_id", "type", "text", "tokenizer", "raw"),
                ts,
                Map.of("name", "level", "type", "text"),
                Map.of("name", "service", "type", "text", "tokenizer", "raw"),
                Map.of("name", "message", "type", "text"),
                Map.of("name", "anomaly", "type", "bool", "fast", true),
                Map.of("name", "severity", "type", "text", "tokenizer", "raw"),
                Map.of("name", "reason", "type", "text"));
    }

    /**
     * Tick the indexer metrics counter for the result.
     *
     * @param result   the produced {@link IndexAdminResult}
     * @param tenantId tenant id from the spec, or {@code null} for
     *                 calls that don't carry one (e.g. {@code dropIndex})
     */
    private void tick(final IndexAdminResult result, final String tenantId) {
        this.metrics.incIndexAdmin(result.backend(), result.outcome(), tenantId);
    }

    @Override
    public IndexAdminResult applyRetention(final IndexSpec spec,
                                           final RetentionPolicy policy) {
        if (spec == null) {
            final IndexAdminResult result = IndexAdminResult.permanentFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:null-spec");
            tick(result, null);
            return result;
        }
        if (policy == null) {
            final IndexAdminResult result = IndexAdminResult.permanentFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:null-policy");
            tick(result, spec.tenantId());
            return result;
        }

        final long endTimestamp = this.clock.instant()
                .minus(policy.ttl()).getEpochSecond();
        final String body;
        try {
            body = this.mapper.writeValueAsString(
                    renderRetentionBody(endTimestamp));
        } catch (final JsonProcessingException ex) {
            LOG.warn("quickwit applyRetention body serialisation failed "
                    + "tenantId={} indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            final IndexAdminResult result = IndexAdminResult.transientFailure(
                    IndexAdminResult.BACKEND_QUICKWIT, "quickwit:unknown");
            tick(result, spec.tenantId());
            return result;
        }

        try {
            this.restClient.post()
                    .uri(DELETE_TASKS_PATH, spec.indexId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            final IndexAdminResult result = IndexAdminResult.retentionApplied(
                    IndexAdminResult.BACKEND_QUICKWIT);
            tick(result, spec.tenantId());
            return result;
        } catch (final RestClientResponseException ex) {
            LOG.warn("quickwit applyRetention POST non-2xx tenantId={} "
                    + "indexId={} status={}: {}",
                    spec.tenantId(), spec.indexId(),
                    ex.getStatusCode().value(), ex.getMessage());
            final IndexAdminResult result = this.template.classifyHttp(ex);
            tick(result, spec.tenantId());
            return result;
        } catch (final ResourceAccessException ex) {
            LOG.warn("quickwit applyRetention POST transport failure "
                    + "tenantId={} indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            final IndexAdminResult result = this.template.classifyTransport(ex);
            tick(result, spec.tenantId());
            return result;
        } catch (final RuntimeException ex) {
            LOG.warn("quickwit applyRetention POST unexpected failure "
                    + "tenantId={} indexId={}: {}",
                    spec.tenantId(), spec.indexId(), ex.getMessage());
            final IndexAdminResult result = this.template.classifyUnknown(ex);
            tick(result, spec.tenantId());
            return result;
        }
    }

    /**
     * Render the Quickwit {@code DeleteQuery} body for retention
     * application (P7.2 / ADR-0040 D4). Quickwit schedules deletion
     * of every doc where {@code timestamp_field &lt; end_timestamp};
     * {@code query="*"} matches every doc, and the cutoff filters
     * down to the expired window. {@code start_timestamp} is
     * intentionally omitted so the unbounded past is included.
     *
     * @param endTimestampSeconds cutoff in epoch seconds (UTC)
     * @return ordered map suitable for Jackson encoding
     */
    Map<String, Object> renderRetentionBody(final long endTimestampSeconds) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", "*");
        body.put("end_timestamp", endTimestampSeconds);
        return body;
    }

    /**
     * Expose the bound configuration for tests + diagnostics.
     *
     * @return the bound {@link QuickwitProperties}
     */
    QuickwitProperties properties() {
        return this.properties;
    }
}
