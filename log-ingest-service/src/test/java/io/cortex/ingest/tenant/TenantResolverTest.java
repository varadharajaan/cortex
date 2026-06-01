package io.cortex.ingest.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cortex.ingest.constants.ErrorCodes;
import io.cortex.ingest.exception.ApplicationException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TenantResolver} covering the rejection
 * branches not exercised by the integration tests
 * (P4.1 / LD3 / Rule 12.5 / 13.4).
 */
class TenantResolverTest {

    /** SUT - stateless, reused across tests. */
    private final TenantResolver resolver = new TenantResolver();

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
     * A non-blank header is trimmed and returned verbatim.
     */
    @Test
    void validHeaderIsTrimmedAndReturned() {
        assertThat(this.resolver.resolve("  cortex-dev  ")).isEqualTo("cortex-dev");
    }
}
