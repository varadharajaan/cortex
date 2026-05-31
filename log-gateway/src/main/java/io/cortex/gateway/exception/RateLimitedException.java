package io.cortex.gateway.exception;

import io.cortex.gateway.constants.ErrorCodes;
import java.io.Serial;
import lombok.Getter;

/**
 * Thrown by {@link io.cortex.gateway.filter.RateLimitFilter} when the
 * caller's bucket is empty (B5, RFC 6585 \u00a74). Carries the bucket
 * capacity and the seconds until the next token is available so the
 * global handler can populate {@code X-RateLimit-Limit},
 * {@code X-RateLimit-Remaining}, {@code X-RateLimit-Reset}, and
 * {@code Retry-After} consistently.
 */
@Getter
public class RateLimitedException extends ApplicationException {

    /** Serial version UID. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** Bucket capacity returned in {@code X-RateLimit-Limit}. */
    private final long capacity;

    /** Tokens remaining; always zero when this is thrown, kept for symmetry. */
    private final long remaining;

    /** Seconds until the next token refills; returned in {@code Retry-After}. */
    private final long retryAfterSeconds;

    /**
     * Builds a rate-limit exception with the diagnostic fields needed
     * by the handler to render correct response headers.
     *
     * @param capacity          bucket capacity
     * @param remaining         tokens remaining at the moment of rejection (zero)
     * @param retryAfterSeconds seconds until refill; must be positive
     */
    public RateLimitedException(final long capacity, final long remaining, final long retryAfterSeconds) {
        super(ErrorCodes.RATE_LIMITED, "rate limit exceeded");
        this.capacity = capacity;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Builds a rate-limit exception carrying an explicit error code so the
     * NL sub-bucket (P3.3 / ADR-0018) can surface
     * {@link ErrorCodes#NL_QUERY_RATE_LIMITED} while the global bucket
     * keeps surfacing {@link ErrorCodes#RATE_LIMITED}. Both still map to
     * HTTP 429 + {@code Retry-After}; only the response body's
     * {@code errorCode} differs.
     *
     * @param code              stable error code to surface in the problem body
     * @param capacity          bucket capacity
     * @param remaining         tokens remaining at the moment of rejection (zero)
     * @param retryAfterSeconds seconds until refill; must be positive
     */
    public RateLimitedException(
            final ErrorCodes code,
            final long capacity,
            final long remaining,
            final long retryAfterSeconds) {
        super(code, "rate limit exceeded");
        this.capacity = capacity;
        this.remaining = remaining;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
