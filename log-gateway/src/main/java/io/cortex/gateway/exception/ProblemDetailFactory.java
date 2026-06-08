package io.cortex.gateway.exception;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.LogFields;
import java.net.URI;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 7807 {@link ProblemDetail} bodies shared by the
 * {@link GlobalExceptionHandler} (the REST / {@code @RequestMapping}
 * surface) and the {@link RateLimitProblemExceptionResolver} (the
 * functional GraphQL HTTP endpoint).
 *
 * <p>Centralising the body shape here guarantees the two surfaces emit
 * byte-identical problem documents for the same error -- the
 * P9.0a / P9.0b parity contract (ADR-0049 Amendment 2). Before P9.0b
 * the builder lived as a private method on {@link GlobalExceptionHandler}
 * and the GraphQL surface had no equivalent, so a rate-limit rejection
 * on {@code /graphql} escaped as a generic {@code 500} instead of the
 * documented {@code 429} problem body.</p>
 */
final class ProblemDetailFactory {

    /** Custom problem field name for the stable error code. */
    static final String FIELD_ERROR_CODE = "errorCode";

    /** Custom problem field name for the correlation id. */
    static final String FIELD_TRACE_ID = "traceId";

    /** Custom problem field name for the response timestamp. */
    static final String FIELD_TIMESTAMP = "timestamp";

    private ProblemDetailFactory() {
        // Static factory holder; not instantiable.
    }

    /**
     * Builds a populated {@link ProblemDetail} including the custom
     * gateway fields ({@code errorCode}, {@code timestamp}) and the
     * correlation id from MDC ({@code traceId}) when present.
     *
     * @param status      HTTP status to return
     * @param code        stable error code surfaced as {@code errorCode}
     * @param detail      human-readable detail
     * @param instanceUri request URI used for the {@code instance} field
     *                    (nullable; omitted when {@code null})
     * @return populated problem detail
     */
    static ProblemDetail build(
            final HttpStatus status,
            final ErrorCodes code,
            final String detail,
            final String instanceUri) {
        final ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        if (instanceUri != null) {
            problem.setInstance(URI.create(instanceUri));
        }
        problem.setProperty(FIELD_ERROR_CODE, code.name());
        problem.setProperty(FIELD_TIMESTAMP, OffsetDateTime.now().toString());
        final String traceId = MDC.get(LogFields.TRACE_ID);
        if (traceId != null) {
            problem.setProperty(FIELD_TRACE_ID, traceId);
        }
        return problem;
    }
}
