package io.cortex.ingest.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceJwtClaimExtractor} (P4.3 /
 * plan.md row 169). Confirms the lenient extraction contract:
 * any malformed input returns {@link Optional#empty()} (never
 * throws), a valid compact-serialised JWT with a {@code tid}
 * claim returns the trimmed claim value.
 *
 * <p>Signature verification + issuer / audience / expiry checks
 * are deferred to P5.x / B7.1 (ADR-0024), so these tests
 * intentionally construct unsigned tokens -- they pass the
 * P4.3 contract because the extractor never validates the
 * signature segment.</p>
 */
class ServiceJwtClaimExtractorTest {

    /** Shared Jackson decoder; pinned per @BeforeEach for clarity. */
    private ObjectMapper mapper;

    /** Subject under test. */
    private ServiceJwtClaimExtractor extractor;

    /** Fresh wiring per test. */
    @BeforeEach
    void initExtractor() {
        this.mapper = new ObjectMapper();
        this.extractor = new ServiceJwtClaimExtractor(this.mapper);
    }

    /** A well-formed JWT with {@code tid} returns the claim value. */
    @Test
    void wellFormedJwtReturnsTidClaim() {
        final String jwt = stubJwt("{\"tid\":\"cortex-prod\"}");

        final Optional<String> result = this.extractor.extractTenantId(jwt);

        assertThat(result).contains("cortex-prod");
    }

    /** Trims surrounding whitespace inside the claim value. */
    @Test
    void tidClaimValueIsTrimmed() {
        final String jwt = stubJwt("{\"tid\":\"  cortex-prod  \"}");

        final Optional<String> result = this.extractor.extractTenantId(jwt);

        assertThat(result).contains("cortex-prod");
    }

    /** Null header is rejected as empty (no exception). */
    @Test
    void nullHeaderReturnsEmpty() {
        assertThat(this.extractor.extractTenantId(null)).isEmpty();
    }

    /** Blank header is rejected as empty (no exception). */
    @Test
    void blankHeaderReturnsEmpty() {
        assertThat(this.extractor.extractTenantId("   ")).isEmpty();
    }

    /** A header that is not the 3-segment compact form returns empty. */
    @Test
    void wrongSegmentCountReturnsEmpty() {
        assertThat(this.extractor.extractTenantId("only.two")).isEmpty();
        assertThat(this.extractor.extractTenantId("a.b.c.d")).isEmpty();
    }

    /** A non-base64 payload segment returns empty (no exception). */
    @Test
    void invalidBase64PayloadReturnsEmpty() {
        final String jwt = "h." + "!not-base64!" + ".s";

        assertThat(this.extractor.extractTenantId(jwt)).isEmpty();
    }

    /** A payload that is not valid JSON returns empty (no exception). */
    @Test
    void nonJsonPayloadReturnsEmpty() {
        final String jwt = "h." + base64Url("not json at all") + ".s";

        assertThat(this.extractor.extractTenantId(jwt)).isEmpty();
    }

    /** A JSON payload missing the {@code tid} claim returns empty. */
    @Test
    void missingTidClaimReturnsEmpty() {
        final String jwt = stubJwt("{\"sub\":\"someone\"}");

        assertThat(this.extractor.extractTenantId(jwt)).isEmpty();
    }

    /** A non-textual {@code tid} (e.g. number) returns empty. */
    @Test
    void nonTextualTidClaimReturnsEmpty() {
        final String jwt = stubJwt("{\"tid\":42}");

        assertThat(this.extractor.extractTenantId(jwt)).isEmpty();
    }

    /** A blank textual {@code tid} returns empty. */
    @Test
    void blankTidClaimReturnsEmpty() {
        final String jwt = stubJwt("{\"tid\":\"   \"}");

        assertThat(this.extractor.extractTenantId(jwt)).isEmpty();
    }

    /** The canonical claim-name constant matches the documented contract. */
    @Test
    void claimNameConstantIsTid() {
        assertThat(ServiceJwtClaimExtractor.CLAIM_TENANT_ID).isEqualTo("tid");
    }

    /**
     * Builds a 3-segment compact JWT whose payload is the
     * supplied JSON. Header and signature segments are dummy
     * values -- the extractor never validates them in P4.3.
     *
     * @param payloadJson literal JSON payload (e.g. {@code {"tid":"x"}})
     * @return compact JWT serialisation
     */
    private static String stubJwt(final String payloadJson) {
        return "h." + base64Url(payloadJson) + ".s";
    }

    /**
     * URL-safe base64 encoder without padding (RFC 7515 sec 2).
     *
     * @param raw plain bytes
     * @return URL-safe base64 string, no padding
     */
    private static String base64Url(final String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
