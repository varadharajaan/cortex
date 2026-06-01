package io.cortex.echo.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.echo.constants.HeaderNames;
import io.cortex.echo.constants.LogFields;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link CorrelationIdFilter}: ensures the trace id is
 * resolved, exposed in MDC for the duration of the chain, echoed as a
 * response header, and cleared after the chain returns (rule 17.5).
 */
class CorrelationIdFilterTest {

    /** Filter under test. */
    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    /**
     * Honours an inbound {@code X-Request-Id} header verbatim.
     *
     * @throws ServletException on filter chain failure
     * @throws IOException      on filter chain failure
     */
    @Test
    void honoursInboundRequestIdHeader() throws ServletException, IOException {
        final String inbound = "incoming-id-42";
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderNames.X_REQUEST_ID, inbound);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicReference<String> seenDuringChain = new AtomicReference<>();
        final FilterChain chain = (req, res) -> seenDuringChain.set(MDC.get(LogFields.TRACE_ID));

        this.filter.doFilter(request, response, chain);

        assertThat(seenDuringChain.get()).isEqualTo(inbound);
        assertThat(response.getHeader(HeaderNames.X_REQUEST_ID)).isEqualTo(inbound);
        assertThat(MDC.get(LogFields.TRACE_ID)).isNull();
    }

    /**
     * Generates a UUID trace id when the inbound header is absent.
     *
     * @throws ServletException on filter chain failure
     * @throws IOException      on filter chain failure
     */
    @Test
    void mintsUuidWhenInboundHeaderMissing() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final AtomicReference<String> seenDuringChain = new AtomicReference<>();
        final FilterChain chain = (req, res) -> seenDuringChain.set(MDC.get(LogFields.TRACE_ID));

        this.filter.doFilter(request, response, chain);

        final String echoed = response.getHeader(HeaderNames.X_REQUEST_ID);
        assertThat(echoed).isNotBlank();
        UUID.fromString(echoed);
        assertThat(seenDuringChain.get()).isEqualTo(echoed);
        assertThat(MDC.get(LogFields.TRACE_ID)).isNull();
    }

    /**
     * Treats a blank inbound header as missing and mints a UUID.
     *
     * @throws ServletException on filter chain failure
     * @throws IOException      on filter chain failure
     */
    @Test
    void mintsUuidWhenInboundHeaderBlank() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderNames.X_REQUEST_ID, "   ");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = (req, res) -> { };

        this.filter.doFilter(request, response, chain);

        final String echoed = response.getHeader(HeaderNames.X_REQUEST_ID);
        assertThat(echoed).isNotBlank();
        UUID.fromString(echoed);
    }

    /** MDC is cleared even when the downstream chain throws. */
    @Test
    void clearsMdcEvenWhenChainThrows() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HeaderNames.X_REQUEST_ID, "id-on-error");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = (req, res) -> {
            throw new ServletException("boom");
        };

        try {
            this.filter.doFilter(request, response, chain);
        } catch (final ServletException | IOException expected) {
            // expected
        }

        assertThat(MDC.get(LogFields.TRACE_ID)).isNull();
    }

    /** Filter declares the highest Spring precedence so it runs before all others. */
    @Test
    void runsAtHighestPrecedence() {
        assertThat(this.filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
