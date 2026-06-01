package io.cortex.ingest.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.exception.ApplicationException;
import io.cortex.ingest.security.ServiceJwtClaimExtractor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TenantResolver} covering the rejection
 * branches not exercised by the integration tests, plus the P4.3
 * JWT-first / header-fallback resolution contract
 * (P4.1 / P4.3 / LD3 / Rule 12.5 / 13.4).
 */
class TenantResolverTest {

    /** Shared Jackson decoder for the JWT claim extractor. */
    private final ObjectMapper mapper = new ObjectMapper();

    /** SUT - stateless, reused across tests. */
    private final TenantResolver resolver =
            new TenantResolver(new ServiceJwtClaimExtractor(this.mapper));

    /** Default constructor used by JUnit. */
    TenantResolverTest() {
        // no state
    }

    /**
     * A {@code null} header value triggers
     * {@link ErrorCodes#VALIDATION_FAILED}.
     */
    @Test
    void nullHeaderIsRejected() {
        assertThatThrownBy(() -> this.resolver.resolve(null))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    /**
     * A whitespace-only header value triggers
     * {@link ErrorCodes#VALIDATION_FAILED}.
     */
    @Test
    void blankHeaderIsRejected() {
        assertThatThrownBy(() -> this.resolver.resolve("   "))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    /**
     * A non-blank header is trimmed and returned verbatim when no
     * JWT is present.
     */
    @Test
    void validHeaderIsTrimmedAndReturned() {
        assertThat(this.resolver.resolve("  cortex-dev  ")).isEqualTo("cortex-dev");
    }

    /**
     * The {@code tid} claim of the inbound JWT MUST win over the
     * {@code X-Tenant-Id} header when both are present (P4.3).
     */
    @Test
    void jwtTidClaimOverridesHeader() {
        final String jwt = stubJwt("{\"tid\":\"tenant-from-jwt\",\"iss\":\"cortex\"}");
        assertThat(this.resolver.resolve(jwt, "tenant-from-header"))
                .isEqualTo("tenant-from-jwt");
    }

    /**
     * When the JWT carries no usable {@code tid} claim, the
     * resolver falls back to the {@code X-Tenant-Id} header.
     */
    @Test
    void headerFallbackWhenJwtClaimMissing() {
        final String jwt = stubJwt("{\"iss\":\"cortex\"}");
        assertThat(this.resolver.resolve(jwt, "tenant-from-header"))
                .isEqualTo("tenant-from-header");
    }

    /**
     * When the JWT is malformed (not three dot-separated segments)
     * the extractor returns empty and the resolver falls back to
     * the header.
     */
    @Test
    void malformedJwtFallsBackToHeader() {
        assertThat(this.resolver.resolve("not-a-jwt", "tenant-from-header"))
                .isEqualTo("tenant-from-header");
    }

    /**
     * When BOTH the JWT and the header yield nothing, the resolver
     * rejects with {@link ErrorCodes#VALIDATION_FAILED} so the
     * global handler surfaces an RFC 7807 400.
     */
    @Test
    void bothInputsBlankIsRejected() {
        assertThatThrownBy(() -> this.resolver.resolve(null, "   "))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.VALIDATION_FAILED);
    }

    /**
     * Builds a syntactically valid compact-serialised JWT whose
     * payload contains the supplied JSON. Header and signature
     * segments are arbitrary placeholders since this resolver
     * does not verify signatures in P4.3 (deferred to P5.x).
     *
     * @param payloadJson JSON object string for the payload
     * @return three-segment JWT string
     */
    private static String stubJwt(final String payloadJson) {
        final String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        final String payload = base64Url(payloadJson);
        return header + "." + payload + ".sig";
    }

    /**
     * URL-safe base64 encoding of the supplied string without
     * padding.
     *
     * @param raw UTF-8 source string
     * @return URL-safe base64 encoding
     */
    private static String base64Url(final String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
