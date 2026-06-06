package io.cortex.remediation.dispatch;

import io.cortex.remediation.constants.RemediationHttp;
import io.cortex.remediation.parse.AnomalyEvent;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Channel-agnostic dispatch template that owns the outer try/catch,
 * HTTP-status classification, and transport-failure classification
 * shared by every REST-backed {@link RemediationDispatcher}
 * (P6.0a / ADR-0036).
 *
 * <p>Used by composition (not inheritance) per Effective Java item
 * 18: every concrete adapter holds an instance of this template
 * parameterised with its channel id, and delegates {@code dispatch}
 * to the template. The template handles the SPI contract
 * (no-throw on transient failure per ADR-0032 D6 + D7 + LD117) and
 * the outcome-table mapping ({@code 429} -&gt; transient,
 * {@code 5xx} -&gt; transient, other {@code 4xx} -&gt; permanent,
 * timeout -&gt; transient, IO -&gt; transient, unknown -&gt;
 * transient) so the adapters carry only the channel-specific body
 * shape + endpoint + configured-check logic.</p>
 */
@Slf4j
@RequiredArgsConstructor
final class RestDispatchTemplate {

    private final String channelId;

    /**
     * Run a single dispatch through the shared error-handling shell.
     *
     * @param event       the parsed anomaly event (may be null;
     *                    null short-circuits to {@code skipped})
     * @param configCheck supplier that returns {@code true} when the
     *                    adapter is configured (e.g. webhook URL or
     *                    routing key present); {@code false} short-
     *                    circuits to {@code skipped (unconfigured)}
     * @param executor    side-effecting call that performs the
     *                    actual HTTP POST and may throw the three
     *                    expected RestClient exceptions
     * @return the channel-specific {@link DispatchResult} verdict;
     *         never throws on transient failures (ADR-0032 D6)
     */
    DispatchResult dispatch(final AnomalyEvent event,
                            final BooleanSupplier configCheck,
                            final Consumer<AnomalyEvent> executor) {
        if (event == null) {
            return DispatchResult.skipped(channelId + ":null-event");
        }
        if (!configCheck.getAsBoolean()) {
            return DispatchResult.skipped(channelId + ":unconfigured");
        }
        try {
            executor.accept(event);
            log.info("{} dispatched eventId={} tenantId={} severity={}",
                    channelId, event.eventId(), event.tenantId(), event.severity());
            return DispatchResult.dispatched(channelId);
        } catch (RestClientResponseException ex) {
            return classifyHttp(event, ex);
        } catch (ResourceAccessException ex) {
            return classifyTransport(event, ex);
        } catch (RuntimeException ex) {
            log.warn("{} unexpected failure eventId={} tenantId={}: {}",
                    channelId, event.eventId(), event.tenantId(), ex.getMessage());
            return DispatchResult.transientFailure(channelId, channelId + ":unknown");
        }
    }

    private DispatchResult classifyHttp(final AnomalyEvent event,
                                        final RestClientResponseException ex) {
        final HttpStatusCode status = ex.getStatusCode();
        final int code = status.value();
        log.warn("{} non-2xx eventId={} tenantId={} status={}: {}",
                channelId, event.eventId(), event.tenantId(), code, ex.getMessage());
        if (code == RemediationHttp.TOO_MANY_REQUESTS) {
            return DispatchResult.transientFailure(channelId, channelId + ":429");
        }
        if (status.is5xxServerError()) {
            return DispatchResult.transientFailure(channelId, channelId + ":5xx:" + code);
        }
        return DispatchResult.permanentFailure(channelId, channelId + ":4xx:" + code);
    }

    private DispatchResult classifyTransport(final AnomalyEvent event,
                                             final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        final boolean timeout = cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof TimeoutException;
        log.warn("{} transport failure eventId={} tenantId={} timeout={}: {}",
                channelId, event.eventId(), event.tenantId(), timeout, ex.getMessage());
        return DispatchResult.transientFailure(channelId,
                timeout ? channelId + ":timeout" : channelId + ":transport");
    }
}
