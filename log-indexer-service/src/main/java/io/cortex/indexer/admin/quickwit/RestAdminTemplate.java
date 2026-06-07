package io.cortex.indexer.admin.quickwit;

import io.cortex.indexer.admin.IndexAdminResult;
import io.cortex.indexer.constants.IndexerHttp;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Channel-agnostic outcome classification template for
 * {@link io.cortex.indexer.admin.QuickwitIndexAdmin} adapters
 * (P7.1 / ADR-0039 D3; mirror of the P6.0a
 * {@code RestDispatchTemplate} composition pattern, ADR-0036).
 *
 * <p>Effective Java item 18 -- favour composition over inheritance.
 * The single {@link QuickwitHttpAdmin} caller orchestrates the
 * {@code GET}-then-{@code POST}-or-{@code DELETE} HTTP flow itself
 * (since {@code ensureIndex} and {@code dropIndex} have different
 * happy-path verbs) and delegates the catch arms to the three
 * {@code classify*} helpers below. When the next backend lands
 * (e.g. OpenSearch in a hypothetical P9.x), it can reuse the same
 * template by passing its own {@code backendId} -- the outcome
 * table is identical.</p>
 *
 * <p><strong>Outcome table</strong> (ADR-0039 D3):</p>
 * <ul>
 *   <li>HTTP {@code 429} -&gt; transient {@code &lt;backend&gt;:429}</li>
 *   <li>HTTP {@code 5xx} -&gt; transient {@code &lt;backend&gt;:5xx:&lt;n&gt;}</li>
 *   <li>HTTP other {@code 4xx} -&gt; permanent {@code &lt;backend&gt;:4xx:&lt;n&gt;}</li>
 *   <li>{@link HttpTimeoutException}/{@link TimeoutException} cause
 *       -&gt; transient {@code &lt;backend&gt;:timeout}</li>
 *   <li>other {@link ResourceAccessException} -&gt; transient
 *       {@code &lt;backend&gt;:transport}</li>
 *   <li>unexpected {@link RuntimeException} -&gt; transient
 *       {@code &lt;backend&gt;:unknown}</li>
 * </ul>
 *
 * <p>The template intentionally does <strong>not</strong> handle
 * the HTTP {@code 404} bucket -- that's caller-specific:
 * {@code ensureIndex} reads 404 as "needs create" (control flow,
 * not outcome) while {@code dropIndex} reads 404 as the success
 * outcome {@code dropped} per SPI idempotence contract (ADR-0038
 * D5). Pulling 404 into the template would force one of those two
 * callers into an awkward post-template fixup.</p>
 */
@RequiredArgsConstructor
final class RestAdminTemplate {

    /** Backend identifier tag stamped into every result. */
    private final String backendId;

    /**
     * Classify a non-2xx HTTP response surfaced as
     * {@link RestClientResponseException} into an
     * {@link IndexAdminResult}.
     *
     * @param ex the response exception thrown by {@code .retrieve()}
     * @return transient outcome for 429/5xx, permanent for other 4xx
     */
    IndexAdminResult classifyHttp(final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == IndexerHttp.TOO_MANY_REQUESTS) {
            return IndexAdminResult.transientFailure(
                    this.backendId, this.backendId + ":429");
        }
        if (code >= IndexerHttp.SERVER_ERROR_FLOOR) {
            return IndexAdminResult.transientFailure(
                    this.backendId, this.backendId + ":5xx:" + code);
        }
        return IndexAdminResult.permanentFailure(
                this.backendId, this.backendId + ":4xx:" + code);
    }

    /**
     * Classify a transport-layer failure surfaced as
     * {@link ResourceAccessException} into a transient
     * {@link IndexAdminResult}. Cause-based discrimination
     * separates {@code timeout} (JDK
     * {@link HttpTimeoutException} or
     * {@link TimeoutException}) from generic {@code transport}
     * (connection reset, DNS, TLS handshake, etc.).
     *
     * @param ex the transport exception thrown by {@code .retrieve()}
     * @return transient outcome tagged {@code timeout} or {@code transport}
     */
    IndexAdminResult classifyTransport(final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        if (cause instanceof HttpTimeoutException
                || cause instanceof TimeoutException) {
            return IndexAdminResult.transientFailure(
                    this.backendId, this.backendId + ":timeout");
        }
        return IndexAdminResult.transientFailure(
                this.backendId, this.backendId + ":transport");
    }

    /**
     * Classify any unexpected {@link RuntimeException} (e.g.
     * Jackson serialization error, NullPointerException from a
     * bug) into a transient outcome so the SPI contract holds --
     * the SPI must never propagate an exception to the caller.
     *
     * @param ex the unexpected exception
     * @return transient outcome tagged {@code unknown}
     */
    IndexAdminResult classifyUnknown(final RuntimeException ex) {
        // ex is captured by the caller's log line; this method only
        // produces the result envelope.
        return IndexAdminResult.transientFailure(
                this.backendId, this.backendId + ":unknown");
    }
}
