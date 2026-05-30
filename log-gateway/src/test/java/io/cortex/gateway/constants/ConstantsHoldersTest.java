package io.cortex.gateway.constants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests that exercise the constants holders so JaCoCo records
 * their (private) constructors and that the {@link AssertionError}
 * guard fires (rule 8.8).
 */
class ConstantsHoldersTest {

    /** {@link ApiPaths} exposes the expected literals and is non-instantiable. */
    @Test
    void apiPathsExposesExpectedValues() {
        assertThat(ApiPaths.API_V1).isEqualTo("/api/v1");
        assertThat(ApiPaths.HEALTH).isEqualTo("/api/v1/health");
        assertReflectiveCtorThrows(ApiPaths.class);
    }

    /** {@link HeaderNames} exposes the expected literals and is non-instantiable. */
    @Test
    void headerNamesExposesExpectedValues() {
        assertThat(HeaderNames.X_REQUEST_ID).isEqualTo("X-Request-Id");
        assertThat(HeaderNames.X_TENANT_ID).isEqualTo("X-Tenant-Id");
        assertThat(HeaderNames.X_API_KEY).isEqualTo("X-Api-Key");
        assertReflectiveCtorThrows(HeaderNames.class);
    }

    /** {@link LogFields} exposes the expected MDC keys and is non-instantiable. */
    @Test
    void logFieldsExposesExpectedValues() {
        assertThat(LogFields.TRACE_ID).isEqualTo("traceId");
        assertThat(LogFields.TENANT_ID).isEqualTo("tenantId");
        assertThat(LogFields.USER_ID).isEqualTo("userId");
        assertReflectiveCtorThrows(LogFields.class);
    }

    /** {@link ErrorCodes} enumerates every stable error bucket. */
    @Test
    void errorCodesEnumCoversEveryBucket() {
        assertThat(ErrorCodes.values())
                .containsExactlyInAnyOrder(
                        ErrorCodes.VALIDATION_FAILED,
                        ErrorCodes.BAD_REQUEST,
                        ErrorCodes.UNAUTHENTICATED,
                        ErrorCodes.FORBIDDEN,
                        ErrorCodes.NOT_FOUND,
                        ErrorCodes.RATE_LIMITED,
                        ErrorCodes.UPSTREAM_UNAVAILABLE,
                        ErrorCodes.INTERNAL_ERROR);
        assertThat(ErrorCodes.valueOf("BAD_REQUEST")).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    /**
     * Reflectively invokes the holder's private constructor and asserts
     * that it throws {@link AssertionError}.
     *
     * @param holder constants holder class to probe
     */
    private static void assertReflectiveCtorThrows(final Class<?> holder) {
        assertThatThrownBy(() -> {
            final Constructor<?> ctor = holder.getDeclaredConstructor();
            ctor.setAccessible(true);
            try {
                ctor.newInstance();
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(AssertionError.class);
    }
}
