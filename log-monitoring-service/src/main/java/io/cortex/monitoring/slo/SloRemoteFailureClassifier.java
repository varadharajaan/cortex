package io.cortex.monitoring.slo;

import io.cortex.monitoring.constants.MonitoringHttp;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bounded HTTP/transport failure mapping shared by remote SLO
 * engines.
 */
final class SloRemoteFailureClassifier {

    private SloRemoteFailureClassifier() {
    }

    static SloSnapshot classifyHttp(final String backend,
                                    final SloDefinition def,
                                    final RestClientResponseException ex) {
        final int code = ex.getStatusCode().value();
        if (code == MonitoringHttp.TOO_MANY_REQUESTS) {
            return SloSnapshot.transientFailure(backend, def,
                    backend + ":429");
        }
        if (code >= MonitoringHttp.SERVER_ERROR_FLOOR) {
            return SloSnapshot.transientFailure(backend, def,
                    backend + ":5xx:" + code);
        }
        return SloSnapshot.permanentFailure(backend, def,
                backend + ":4xx:" + code);
    }

    static SloSnapshot classifyTransport(final String backend,
                                         final SloDefinition def,
                                         final ResourceAccessException ex) {
        final Throwable cause = ex.getCause();
        if (cause instanceof HttpTimeoutException
                || cause instanceof TimeoutException) {
            return SloSnapshot.transientFailure(backend, def,
                    backend + ":timeout");
        }
        return SloSnapshot.transientFailure(backend, def,
                backend + ":transport");
    }
}
