package io.cortex.echo.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.cortex.echo.dto.EchoResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Echo controller for the throwaway log-echo-service stub
 * (ADR-0016).
 *
 * <p>Mirrors the inbound request back to the caller so smoke tests
 * can assert (a) the gateway routed to us via {@code lb://}, (b)
 * bearer / tenant headers survived the hop, and (c) Eureka resolved
 * {@code log-echo-service} correctly.</p>
 *
 * <p>Accepts every HTTP verb and every sub-path under {@code /echo/}
 * so a single endpoint serves all P3.0b..P3.4 smoke scenarios.</p>
 */
@RestController
public class EchoController {

    /**
     * Default constructor used by Spring.
     */
    public EchoController() {
        // no-arg controller; Spring instantiates via reflection
    }

    /**
     * Returns a JSON body describing the inbound request.
     *
     * @param request the inbound servlet request
     * @return HTTP 200 with an {@link EchoResponse} body
     */
    @RequestMapping(
            value = {"/echo", "/echo/**"},
            method = {
                    RequestMethod.GET,
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.PATCH,
                    RequestMethod.DELETE,
                    RequestMethod.HEAD,
                    RequestMethod.OPTIONS},
            produces = MediaType.APPLICATION_JSON_VALUE)
    @SuppressFBWarnings(
            value = "SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING",
            justification = "log-echo-service is a throwaway downstream stub (ADR-0016) "
                    + "behind log-gateway. CSRF protection is enforced at the gateway "
                    + "via the JWT filter chain; this stub never receives anonymous traffic "
                    + "in dev/staging/prod. Accepting all verbs is required for smoke tests "
                    + "to exercise every routing scenario through a single endpoint.")
    public ResponseEntity<EchoResponse> echo(final HttpServletRequest request) {
        return ResponseEntity.ok(new EchoResponse(
                "log-echo-service",
                request.getRequestURI(),
                request.getMethod(),
                copyHeadersLowerCased(request)));
    }

    /**
     * Copies request headers into an ordered map with lowercased keys
     * so smoke tests can do case-insensitive lookups without depending
     * on the servlet container's casing.
     *
     * @param request the inbound servlet request
     * @return unmodifiable ordered map of headers
     */
    private static Map<String, String> copyHeadersLowerCased(final HttpServletRequest request) {
        final Map<String, String> headers = new LinkedHashMap<>();
        final java.util.Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            final String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return Collections.unmodifiableMap(headers);
    }
}