package io.cortex.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.constants.HeaderNames;
import io.cortex.gateway.constants.LogFields;
import jakarta.servlet.ServletException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Unit tests for {@link RateLimitProblemExceptionResolver}
 * (P9.0b / ADR-0049 Amendment 2).
 *
 * <p>Proves the resolver maps a {@link RateLimitedException} thrown from
 * a functional (non-{@link HandlerMethod}) endpoint -- the GraphQL HTTP
 * transport -- into the SAME RFC 7807 {@code 429} body that the REST
 * surface returns via {@link GlobalExceptionHandler}, and that it defers
 * to {@code @ExceptionHandler} for {@link HandlerMethod} handlers so the
 * REST surface is untouched.</p>
 *
 * <p>The Jackson mapper is configured with a local mixin that mirrors
 * Spring Boot's {@code ProblemDetail} serialization (flattening the
 * {@code properties} map to top-level fields) so the asserted body shape
 * matches the production mapper.</p>
 */
class RateLimitProblemExceptionResolverTest {

    /** Request URI used for the functional GraphQL endpoint. */
    private static final String GRAPHQL_URI = "/graphql";

    /** Mixin replicating Spring Boot's ProblemDetail property flattening. */
    private interface ProblemDetailMixin {
        @JsonAnyGetter
        Map<String, Object> getProperties();
    }

    /** Mapper carrying the ProblemDetail mixin so the body matches production. */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .addMixIn(ProblemDetail.class, ProblemDetailMixin.class);

    /** Subject under test. */
    private final RateLimitProblemExceptionResolver resolver =
            new RateLimitProblemExceptionResolver(this.objectMapper);

    /** Clear MDC after each test so the trace id never leaks. */
    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    /**
     * A rate-limit exception from a functional (non-{@code HandlerMethod})
     * endpoint is mapped to a {@code 429} RFC 7807 body with the
     * {@code Retry-After} header, {@code application/problem+json} content
     * type, and the trace id from MDC.
     *
     * @throws Exception if the written body cannot be parsed
     */
    @Test
    void graphQlRateLimitedMapsToRfc7807429() throws Exception {
        MDC.put(LogFields.TRACE_ID, "trace-xyz");
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", GRAPHQL_URI);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final RateLimitedException ex =
                new RateLimitedException(ErrorCodes.NL_QUERY_RATE_LIMITED, 5L, 0L, 30L);

        final ModelAndView mav = this.resolver.resolveException(request, response, null, ex);

        assertThat(mav).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HeaderNames.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getContentAsByteArray()).isNotEmpty();

        final JsonNode body = this.objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(body.get("title").asText()).isEqualTo("Too Many Requests");
        assertThat(body.get("detail").asText()).isEqualTo("rate limit exceeded");
        assertThat(body.get("instance").asText()).isEqualTo(GRAPHQL_URI);
        assertThat(body.get("errorCode").asText()).isEqualTo(ErrorCodes.NL_QUERY_RATE_LIMITED.name());
        assertThat(body.get("traceId").asText()).isEqualTo("trace-xyz");
        assertThat(body.hasNonNull("timestamp")).isTrue();
    }

    /**
     * For a {@link HandlerMethod} handler (the REST surface) the resolver
     * returns {@code null} and writes nothing, deferring to
     * {@code @ExceptionHandler}.
     */
    @Test
    void handlerMethodDefersToExceptionHandler() {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query/nl");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final HandlerMethod handlerMethod = mock(HandlerMethod.class);
        final RateLimitedException ex =
                new RateLimitedException(ErrorCodes.NL_QUERY_RATE_LIMITED, 5L, 0L, 30L);

        final ModelAndView mav = this.resolver.resolveException(request, response, handlerMethod, ex);

        assertThat(mav).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /** A non-rate-limit exception is ignored (returns {@code null}). */
    @Test
    void nonRateLimitExceptionIsIgnored() {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", GRAPHQL_URI);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        final ModelAndView mav = this.resolver.resolveException(
                request, response, null, new IllegalStateException("boom"));

        assertThat(mav).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
    }

    /**
     * A {@link RateLimitedException} wrapped as the cause of another
     * exception is unwrapped and mapped to {@code 429}.
     */
    @Test
    void wrappedRateLimitedExceptionIsUnwrapped() {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", GRAPHQL_URI);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final RateLimitedException cause =
                new RateLimitedException(ErrorCodes.NL_QUERY_RATE_LIMITED, 5L, 0L, 12L);
        final ServletException wrapper = new ServletException("wrapped", cause);

        final ModelAndView mav = this.resolver.resolveException(request, response, null, wrapper);

        assertThat(mav).isNotNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getHeader(HeaderNames.RETRY_AFTER)).isEqualTo("12");
    }

    /** The resolver runs at highest precedence so it sees functional-endpoint errors first. */
    @Test
    void resolverRunsAtHighestPrecedence() {
        assertThat(this.resolver.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
