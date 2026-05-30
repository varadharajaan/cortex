package io.cortex.echo.dto;

import java.util.Map;

/**
 * Immutable response body returned by {@code GET|POST /echo/**}.
 *
 * <p>Used by smoke tests to verify (a) the gateway routed the request
 * to this service, (b) bearer + tenant headers survived the hop, and
 * (c) Eureka resolved {@code lb://log-echo-service} correctly.</p>
 *
 * @param upstream the service identifier that responded (always
 *                 {@code "log-echo-service"})
 * @param path     the request path observed by this service
 * @param method   the HTTP method observed by this service
 * @param headers  inbound request headers (lowercased keys)
 */
public record EchoResponse(
        String upstream,
        String path,
        String method,
        Map<String, String> headers) {
}
