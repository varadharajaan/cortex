package io.cortex.ingest.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.cortex.ingest.constants.HeaderNames;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link ServiceJwtFilter}.
 *
 * <p>Verifies the four behaviours that matter for the P4.0 mTLS-ready
 * scaffold:</p>
 * <ol>
 *   <li>When {@code required=false}, every request flows through.</li>
 *   <li>When {@code required=true}, a request to {@code /api/**}
 *       without the header is rejected with 401.</li>
 *   <li>When {@code required=true}, a request to {@code /api/**}
 *       with the header flows through.</li>
 *   <li>When {@code required=true}, actuator and SpringDoc paths
 *       always flow through (so K8s probes never get 401'd).</li>
 * </ol>
 */
class ServiceJwtFilterTest {

    private static final ServiceJwtProperties DISABLED =
            new ServiceJwtProperties(false, "");

    private static final ServiceJwtProperties ENABLED =
            new ServiceJwtProperties(true, "cortex");

    /**
     * When the filter is disabled, every request passes through and
     * the response status is left untouched.
     *
     * @throws ServletException unexpected
     * @throws IOException      unexpected
     */
    @Test
    void disabledFilterAlwaysProceeds() throws ServletException, IOException {
        final ServiceJwtFilter filter = new ServiceJwtFilter(DISABLED);
        final MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest/batch");
        final MockHttpServletResponse res = new MockHttpServletResponse();
        final FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(MockHttpServletResponse.SC_OK);
    }

    /**
     * When enabled, missing header on /api/** yields 401.
     *
     * @throws ServletException unexpected
     * @throws IOException      unexpected
     */
    @Test
    void enabledFilterRejectsMissingHeader() throws ServletException, IOException {
        final ServiceJwtFilter filter = new ServiceJwtFilter(ENABLED);
        final MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest/batch");
        final MockHttpServletResponse res = new MockHttpServletResponse();
        final FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(MockHttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * When enabled, present header on /api/** flows through.
     *
     * @throws ServletException unexpected
     * @throws IOException      unexpected
     */
    @Test
    void enabledFilterAcceptsPresentHeader() throws ServletException, IOException {
        final ServiceJwtFilter filter = new ServiceJwtFilter(ENABLED);
        final MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ingest/batch");
        req.addHeader(HeaderNames.SERVICE_JWT, "fake-but-present");
        final MockHttpServletResponse res = new MockHttpServletResponse();
        final FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(MockHttpServletResponse.SC_OK);
    }

    /**
     * Actuator paths are skipped even when the filter is enabled.
     *
     * @throws ServletException unexpected
     * @throws IOException      unexpected
     */
    @Test
    void actuatorPathIsSkipped() throws ServletException, IOException {
        final ServiceJwtFilter filter = new ServiceJwtFilter(ENABLED);
        final MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        final MockHttpServletResponse res = new MockHttpServletResponse();
        final FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(MockHttpServletResponse.SC_OK);
    }
}
