package io.cortex.indexer.closer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cortex.indexer.admin.CardinalityBudget;
import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.admin.IndexSpec;
import io.cortex.indexer.admin.QuickwitIndexAdmin;
import io.cortex.indexer.admin.RetentionPolicy;
import io.cortex.indexer.metrics.IndexerMetrics;
import io.cortex.indexer.search.LogSearchClient;
import io.cortex.indexer.search.SearchRequest;
import io.cortex.indexer.search.SearchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;

/**
 * P7.1a cross-phase closer for {@code log-indexer-service}.
 *
 * <p>Closes the P7 epic by booting the full Spring context with the
 * {@code quickwit} backend flipped ON for both
 * {@code cortex.indexer.admin.backend} AND
 * {@code cortex.indexer.search.backend} (default in
 * {@code application.yml} is {@code noop} for both) and exercising
 * every SPI method shipped across P7.0..P7.4 through the autowired
 * beans against a singleton in-process WireMock that stands in for
 * a real Quickwit cluster.</p>
 *
 * <p>What this proves that the per-phase {@code *WireMockIT} classes
 * could not (they construct the adapter directly, bypassing Spring
 * binding):</p>
 * <ul>
 *   <li>The {@code @ConditionalOnProperty} binder gates on
 *       {@link io.cortex.indexer.admin.quickwit.QuickwitHttpAdmin}
 *       ({@code cortex.indexer.admin.backend=quickwit}) and
 *       {@link io.cortex.indexer.search.quickwit.QuickwitHttpSearch}
 *       ({@code cortex.indexer.search.backend=quickwit}) fire when
 *       the property is set and produce live beans the IT autowires.</li>
 *   <li>The
 *       {@link io.cortex.indexer.admin.quickwit.QuickwitHttpConfig#quickwitAdminRestClient}
 *       bean is published only when the admin gate is open, and the
 *       same {@code RestClient} bean is consumed by BOTH the admin
 *       and search adapters (the search adapter has no second
 *       publisher).</li>
 *   <li>The autowired {@link io.cortex.indexer.metrics.IndexerMetrics}
 *       bootstrap loop (LD106 + LD112) sees the live
 *       {@link QuickwitIndexAdmin#backendId()} +
 *       {@link LogSearchClient#backendId()} = {@code quickwit} beans
 *       and pre-registers the per-backend outcome series before the
 *       first admin or search call ticks (so the counter families
 *       are visible on the {@code /actuator/prometheus} scrape from
 *       the moment the context comes up).</li>
 *   <li>Every SPI verdict from every P7.0..P7.4 method increments
 *       the correct {@code (backend, outcome, tenant_id)} tag
 *       triple on {@link IndexerMetrics#METRIC_INDEX_ADMIN_TOTAL}
 *       and {@link IndexerMetrics#METRIC_SEARCH_TOTAL}.</li>
 * </ul>
 *
 * <p>The singleton {@link WireMockServer} is started in a static
 * initialiser so its dynamic port is known by the time the
 * {@link DynamicPropertySource} lambda fires. WireMock stubs +
 * journal are reset in {@link #resetWireMock()} so happy + failure
 * paths in the same class do not contaminate each other (the
 * counter assertions use a delta-from-baseline pattern so test
 * order is irrelevant).</p>
 *
 * <p>The {@code IT_READ_TIMEOUT_SECONDS = 30s} bump (LD123) covers
 * the cold-start JIT warm-up on the first request through the JDK
 * {@link java.net.http.HttpClient} + WireMock stack so the
 * transport-fault classifier does not trip on a slow first request
 * when Failsafe sorts this IT first by class name.</p>
 *
 * <p>Per ADR-0043 D1, this IT serves as the LD73 Leg D for the
 * P7.0..P7.4 ring: it is the automated, CI-protected proof that
 * the full SPI surface works against a wire-format-identical
 * Quickwit stand-in.</p>
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
                "cortex.indexer.admin.backend=quickwit",
                "cortex.indexer.search.backend=quickwit",
                "cortex.indexer.quickwit.request-timeout=30s",
                "cortex.indexer.quickwit.doc-mapping-version=v1",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DisplayName("Quickwit cross-phase closer IT (P7.0..P7.4 SPI through live Spring context)")
class QuickwitCrossPhaseIT {

    /** Shared in-process WireMock server (singleton) on a dynamic port. */
    private static final WireMockServer WIRE_MOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    /** Tenant id reused across every test (mirrors the per-phase WireMock ITs). */
    private static final String TENANT_ID = "tenant-IT";

    /** Index id reused across every test (carries the canonical {@code cortex-<tenantId>-} prefix). */
    private static final String INDEX_ID = "cortex-tenant-IT-v1";

    /** Doc-mapping schema version stamped on every spec (matches
     * {@link io.cortex.indexer.admin.quickwit.QuickwitProperties#DEFAULT_DOC_MAPPING_VERSION}). */
    private static final String DOC_MAPPING_VERSION = "v1";

    /** Quickwit cluster admin path (POST = create; GET = list for budget check). */
    private static final String INDEXES_PATH = "/api/v1/indexes";

    /** Quickwit per-index admin path (GET = existence probe; DELETE = drop). */
    private static final String INDEX_PATH = "/api/v1/indexes/" + INDEX_ID;

    /** Quickwit Delete API path for {@link RetentionPolicy} application (P7.2). */
    private static final String DELETE_TASKS_PATH = "/api/v1/" + INDEX_ID + "/delete-tasks";

    /** Quickwit per-index search path (POST; P7.4). */
    private static final String SEARCH_PATH = "/api/v1/" + INDEX_ID + "/search";

    static {
        WIRE_MOCK.start();
    }

    /**
     * Wires the shared WireMock base URL into the autowired
     * Quickwit {@code RestClient} via the {@code QuickwitProperties}
     * binder + disables Eureka for the embedded context (the IT
     * does not need service registration).
     *
     * @param registry Spring's dynamic property registry
     */
    @DynamicPropertySource
    static void baseProperties(final DynamicPropertyRegistry registry) {
        registry.add("cortex.indexer.quickwit.base-url", WIRE_MOCK::baseUrl);
    }

    private final QuickwitIndexAdmin admin;
    private final LogSearchClient search;
    private final MeterRegistry registry;

    /**
     * Constructor-injection seam mandated by repo Rule 14.1 (no
     * field {@code @Autowired}). {@link TestConstructor.AutowireMode#ALL}
     * resolves every parameter through the autowired Spring
     * context. Mirrors {@code SlackCrossPhaseIT} /
     * {@code JiraCrossPhaseIT} / {@code PagerDutyCrossPhaseIT} in
     * {@code log-remediation-service}.
     *
     * @param admin    autowired admin bean (must be the
     *                 {@code quickwit} backend after the binder gate
     *                 flip)
     * @param search   autowired search bean (must be the
     *                 {@code quickwit} backend)
     * @param registry autowired Micrometer registry hosting both
     *                 counter families
     */
    QuickwitCrossPhaseIT(final QuickwitIndexAdmin admin,
                         final LogSearchClient search,
                         final MeterRegistry registry) {
        this.admin = admin;
        this.search = search;
        this.registry = registry;
    }

    /** Resets WireMock stubs + request journal before every test method. */
    @BeforeEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    // ---------------------------------------------------------------
    // Wiring assertions -- the binder-gate proof. Must come first
    // (display-name + JUnit5 default order both ensure these run early).
    // ---------------------------------------------------------------

    /** Autowired admin bean is the production {@code quickwit} backend, not the noop. */
    @Test
    @DisplayName("autowired QuickwitIndexAdmin bean is the quickwit backend (binder gate)")
    void adminBeanIsQuickwitBackend() {
        assertThat(this.admin).isNotNull();
        assertThat(this.admin.backendId()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
    }

    /** Autowired search bean is the production {@code quickwit} backend, not the noop. */
    @Test
    @DisplayName("autowired LogSearchClient bean is the quickwit backend (binder gate)")
    void searchBeanIsQuickwitBackend() {
        assertThat(this.search).isNotNull();
        assertThat(this.search.backendId()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
    }

    // ---------------------------------------------------------------
    // P7.1 ensureIndex(spec) + dropIndex
    // ---------------------------------------------------------------

    /** GET 404 then POST 200 -> {@code created}; index_admin counter ticks +1. */
    @Test
    @DisplayName("P7.1 ensureIndex creates when absent (GET 404 + POST 200) and ticks admin counter")
    void ensureIndexCreatesWhenAbsent() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse().withStatus(404).withBody("not found")));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"index_id\":\"" + INDEX_ID + "\"}")));

        final double before = adminCounterValue(IndexAdminResult.OUTCOME_CREATED);
        final IndexAdminResult result = this.admin.ensureIndex(sampleSpec());

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_CREATED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(adminCounterValue(IndexAdminResult.OUTCOME_CREATED))
                .isEqualTo(before + 1.0d);
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(INDEXES_PATH))
                .withRequestBody(matchingJsonPath("$.index_id", equalTo(INDEX_ID))));
    }

    /** GET 200 -> {@code exists}; no POST sent; counter ticks +1. */
    @Test
    @DisplayName("P7.1 ensureIndex short-circuits on GET 200 and ticks admin counter (exists)")
    void ensureIndexExistsWhenPresent() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"index_id\":\"" + INDEX_ID + "\"}")));

        final double before = adminCounterValue(IndexAdminResult.OUTCOME_EXISTS);
        final IndexAdminResult result = this.admin.ensureIndex(sampleSpec());

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_EXISTS);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(adminCounterValue(IndexAdminResult.OUTCOME_EXISTS))
                .isEqualTo(before + 1.0d);
    }

    /** DELETE 200 -> {@code dropped}; counter ticks +1.
     *
     * <p>{@code dropIndex(String)} has no {@code IndexSpec} on the
     * SPI (the contract is index-id-only), so the adapter ticks
     * with a {@code null} tenant which the metrics layer coerces
     * to the {@code unknown} sentinel (Part 17 / LD106 + LD112);
     * the test asserts against that exact tag value.</p>
     */
    @Test
    @DisplayName("P7.1 dropIndex on present index returns dropped and ticks admin counter")
    void dropIndexDeletesPresentIndex() {
        WIRE_MOCK.stubFor(delete(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        final double before = adminCounterValue(
                IndexAdminResult.OUTCOME_DROPPED, IndexerMetrics.UNKNOWN);
        final IndexAdminResult result = this.admin.dropIndex(INDEX_ID);

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_DROPPED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(adminCounterValue(
                IndexAdminResult.OUTCOME_DROPPED, IndexerMetrics.UNKNOWN))
                .isEqualTo(before + 1.0d);
    }

    /** DELETE 404 -> {@code dropped} (idempotent); counter ticks +1. */
    @Test
    @DisplayName("P7.1 dropIndex on missing index is idempotent (DELETE 404 -> dropped)")
    void dropIndexIdempotentOnMissing() {
        WIRE_MOCK.stubFor(delete(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        final double before = adminCounterValue(
                IndexAdminResult.OUTCOME_DROPPED, IndexerMetrics.UNKNOWN);
        final IndexAdminResult result = this.admin.dropIndex(INDEX_ID);

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_DROPPED);
        assertThat(adminCounterValue(
                IndexAdminResult.OUTCOME_DROPPED, IndexerMetrics.UNKNOWN))
                .isEqualTo(before + 1.0d);
    }

    // ---------------------------------------------------------------
    // P7.2 applyRetention
    // ---------------------------------------------------------------

    /** POST 200 -> {@code retention_applied}; counter ticks +1. */
    @Test
    @DisplayName("P7.2 applyRetention POSTs delete-tasks and ticks admin counter (retention_applied)")
    void applyRetentionPostsDeleteTasks() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(DELETE_TASKS_PATH))
                .willReturn(aResponse().withStatus(200).withBody("{\"opstamp\":42}")));

        final double before = adminCounterValue(IndexAdminResult.OUTCOME_RETENTION_APPLIED);
        final IndexAdminResult result =
                this.admin.applyRetention(sampleSpec(), new RetentionPolicy(Duration.ofDays(7)));

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_RETENTION_APPLIED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(adminCounterValue(IndexAdminResult.OUTCOME_RETENTION_APPLIED))
                .isEqualTo(before + 1.0d);
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(DELETE_TASKS_PATH))
                .withRequestBody(matchingJsonPath("$.query"))
                .withRequestBody(matchingJsonPath("$.end_timestamp")));
    }

    // ---------------------------------------------------------------
    // P7.3 ensureIndex(spec, budget)
    // ---------------------------------------------------------------

    /** GET list returns 0 indexes + GET 404 + POST 200 -> {@code created}; counter ticks +1. */
    @Test
    @DisplayName("P7.3 ensureIndex+budget creates when under ceiling (counter ticks created)")
    void ensureIndexWithBudgetCreatesWhenUnder() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(200).withBody("[]")));
        WIRE_MOCK.stubFor(get(urlPathEqualTo(INDEX_PATH))
                .willReturn(aResponse().withStatus(404).withBody("not found")));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"index_id\":\"" + INDEX_ID + "\"}")));

        final double before = adminCounterValue(IndexAdminResult.OUTCOME_CREATED);
        final IndexAdminResult result =
                this.admin.ensureIndex(sampleSpec(), new CardinalityBudget(5));

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_CREATED);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(adminCounterValue(IndexAdminResult.OUTCOME_CREATED))
                .isEqualTo(before + 1.0d);
    }

    /** GET list returns >= budget indexes -> {@code permanent_failure / quickwit:budget-exceeded}; counter ticks +1. */
    @Test
    @DisplayName("P7.3 ensureIndex+budget rejects when at/over ceiling (counter ticks permanent_failure)")
    void ensureIndexWithBudgetRejectsWhenOver() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(INDEXES_PATH))
                .willReturn(aResponse().withStatus(200).withBody(
                        "[{\"index_config\":{\"index_id\":\"cortex-tenant-IT-a-v1\"}},"
                        + "{\"index_config\":{\"index_id\":\"cortex-tenant-IT-b-v1\"}}]")));

        final double before = adminCounterValue(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        final IndexAdminResult result =
                this.admin.ensureIndex(sampleSpec(), new CardinalityBudget(2));

        assertThat(result.outcome()).isEqualTo(IndexAdminResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.backend()).isEqualTo(IndexAdminResult.BACKEND_QUICKWIT);
        assertThat(result.reason()).isEqualTo("quickwit:budget-exceeded");
        assertThat(adminCounterValue(IndexAdminResult.OUTCOME_PERMANENT_FAILURE))
                .isEqualTo(before + 1.0d);
    }

    // ---------------------------------------------------------------
    // P7.4 search(SearchRequest)
    // ---------------------------------------------------------------

    /** POST 200 with hits -> {@code search_ok}; search counter ticks +1; hits deserialise. */
    @Test
    @DisplayName("P7.4 search happy path returns hits and ticks search counter (search_ok)")
    void searchHappyPathReturnsHits() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(200).withBody(
                        "{\"num_hits\":2,\"hits\":["
                        + "{\"message\":\"first hit\",\"level\":\"ERROR\"},"
                        + "{\"message\":\"second hit\",\"level\":\"ERROR\"}"
                        + "]}")));

        final double before = searchCounterValue(SearchResult.OUTCOME_SEARCH_OK);
        final SearchResult result =
                this.search.search(new SearchRequest(TENANT_ID, INDEX_ID, "level:ERROR", 10));

        assertThat(result.outcome()).isEqualTo(SearchResult.OUTCOME_SEARCH_OK);
        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(result.numHits()).isEqualTo(2L);
        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0)).containsEntry("message", "first hit");
        assertThat(searchCounterValue(SearchResult.OUTCOME_SEARCH_OK))
                .isEqualTo(before + 1.0d);
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo(SEARCH_PATH))
                .withRequestBody(matchingJsonPath("$.query", equalTo("level:ERROR")))
                .withRequestBody(matchingJsonPath("$.max_hits", equalTo("10"))));
    }

    /** POST 404 -> {@code permanent_failure / quickwit:4xx:404}; search counter ticks +1. */
    @Test
    @DisplayName("P7.4 search 404 returns permanent_failure and ticks search counter")
    void searchPermanentOnHttp404() {
        WIRE_MOCK.stubFor(post(urlPathEqualTo(SEARCH_PATH))
                .willReturn(aResponse().withStatus(404).withBody("index not found")));

        final double before = searchCounterValue(SearchResult.OUTCOME_PERMANENT_FAILURE);
        final SearchResult result =
                this.search.search(new SearchRequest(TENANT_ID, INDEX_ID, "*", 10));

        assertThat(result.outcome()).isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.backend()).isEqualTo(SearchResult.BACKEND_QUICKWIT);
        assertThat(result.reason()).isEqualTo("quickwit:4xx:404");
        assertThat(searchCounterValue(SearchResult.OUTCOME_PERMANENT_FAILURE))
                .isEqualTo(before + 1.0d);
    }

    /** Tenant-prefix mismatch (index id lacks {@code cortex-<tenantId>-} prefix) -> permanent without HTTP.
     *
     * <p>Counter ticks with the REQUEST's tenant id (the offending
     * tenant), not {@link #TENANT_ID}, so dashboards can spot the
     * abusive caller. Per ADR-0042 D3 the guardrail returns BEFORE
     * any HTTP call, so WireMock's request journal stays empty.</p>
     */
    @Test
    @DisplayName("P7.4 search tenant-mismatch returns permanent_failure WITHOUT any HTTP call")
    void searchPermanentOnTenantMismatch() {
        final String offender = "other-tenant";
        final double before = searchCounterValueForTenant(
                SearchResult.OUTCOME_PERMANENT_FAILURE, offender);
        final SearchResult result = this.search.search(
                new SearchRequest(offender, INDEX_ID, "*", 10));

        assertThat(result.outcome()).isEqualTo(SearchResult.OUTCOME_PERMANENT_FAILURE);
        assertThat(result.reason()).isEqualTo("quickwit:tenant-mismatch");
        assertThat(searchCounterValueForTenant(
                SearchResult.OUTCOME_PERMANENT_FAILURE, offender))
                .isEqualTo(before + 1.0d);
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(SEARCH_PATH)));
    }

    // ---------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------

    /** A representative spec reused by every admin test. */
    private static IndexSpec sampleSpec() {
        return new IndexSpec(TENANT_ID, INDEX_ID, DOC_MAPPING_VERSION);
    }

    /**
     * Reads the current value of the
     * {@code cortex.indexer.index_admin_total} counter scoped to a
     * single {@code (backend=quickwit, outcome=<outcome>,
     * tenant_id=tenant-IT)} tag triple. Returns 0 when the
     * timeseries has not yet registered (test-method scope; the
     * bootstrap loop pre-registers the all-{@code unknown}
     * placeholder + per-backend failable outcomes per LD106 + LD112
     * so the family is visible from context-startup but the
     * specific tenant-tagged series may be lazy).
     *
     * @param outcome one of the {@link IndexAdminResult}
     *                {@code OUTCOME_*} constants
     * @return counter value or 0 when the timeseries is absent
     */
    private double adminCounterValue(final String outcome) {
        return adminCounterValue(outcome, TENANT_ID);
    }

    /**
     * Reads the index-admin counter scoped to an arbitrary
     * {@code tenant_id} tag value. Used by the SPI calls that don't
     * carry an {@link IndexSpec} (e.g. {@link
     * io.cortex.indexer.admin.QuickwitIndexAdmin#dropIndex(String)
     * dropIndex(String)} ticks {@code tenant_id=unknown} because
     * the metrics layer coerces a {@code null} tenant to
     * {@link IndexerMetrics#UNKNOWN}).
     *
     * @param outcome  one of the {@link IndexAdminResult}
     *                 {@code OUTCOME_*} constants
     * @param tenantId the {@code tenant_id} tag value to scope to
     * @return counter value or 0 when the timeseries is absent
     */
    private double adminCounterValue(final String outcome, final String tenantId) {
        final Counter counter = Search.in(this.registry)
                .name(IndexerMetrics.METRIC_INDEX_ADMIN_TOTAL)
                .tag("backend", IndexAdminResult.BACKEND_QUICKWIT)
                .tag("outcome", outcome)
                .tag("tenant_id", tenantId)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    /**
     * Reads the current value of the
     * {@code cortex.indexer.search_total} counter scoped to a single
     * {@code (backend=quickwit, outcome=<outcome>, tenant_id=tenant-IT)}
     * tag triple. Returns 0 when the timeseries has not yet
     * registered.
     *
     * @param outcome one of the {@link SearchResult} {@code OUTCOME_*}
     *                constants
     * @return counter value or 0 when the timeseries is absent
     */
    private double searchCounterValue(final String outcome) {
        return searchCounterValueForTenant(outcome, TENANT_ID);
    }

    /**
     * Reads the search counter scoped to an arbitrary {@code tenant_id}
     * tag value. Used by the tenant-mismatch test which ticks with
     * the (offending) request tenant id, not {@link #TENANT_ID}.
     *
     * @param outcome  one of the {@link SearchResult} {@code OUTCOME_*}
     *                 constants
     * @param tenantId the {@code tenant_id} tag value to scope to
     * @return counter value or 0 when the timeseries is absent
     */
    private double searchCounterValueForTenant(final String outcome,
                                               final String tenantId) {
        final Counter counter = Search.in(this.registry)
                .name(IndexerMetrics.METRIC_SEARCH_TOTAL)
                .tag("backend", SearchResult.BACKEND_QUICKWIT)
                .tag("outcome", outcome)
                .tag("tenant_id", tenantId)
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
