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
}
