package io.cortex.gateway.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.cortex.gateway.config.GraphQlScalarConfig;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.NlQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;

/**
 * Slice test for {@link NlQueryGraphQlController}: GraphQL contract
 * for the {@code nlToLogQL(prompt)} root query (P9.0 / ADR-0049).
 *
 * <p>Covers the happy path and propagation of {@code NL_QUERY_INVALID}
 * from the shared {@link NlQueryService} into a populated GraphQL
 * {@code errors[]} array. Other downstream mappings
 * ({@code NL_QUERY_REFUSED}, {@code NL_QUERY_UPSTREAM_FAILED},
 * {@code NL_QUERY_RATE_LIMITED}) are covered end-to-end by
 * {@code io.cortex.gateway.closer.NlQueryRestAndGraphQlParityIT}.</p>
 *
 * <p>Authentication is supplied by {@link WithMockUser}; the slice
 * skips the real filter chain.</p>
 */
@GraphQlTest(NlQueryGraphQlController.class)
@Import(GraphQlScalarConfig.class)
@TestPropertySource(properties = "cortex.gateway.nl-query.enabled=true")
class NlQueryGraphQlControllerTest {

    /** Auto-configured GraphQlTester (bound to the resolver under test). */
    @Autowired private GraphQlTester tester;

    /** Mocked NL service so the slice can simulate model outcomes. */
    @MockBean private NlQueryService service;

    /**
     * Happy path: the resolver returns the structured payload and
     * GraphQL maps fields by name into {@code NlQueryResult}.
     */
    @Test
    @WithMockUser(username = "alice")
    void nlToLogQlReturnsStructuredResultOnHappyPath() {
        final NlQueryResponse expected = new NlQueryResponse(
                "{service=\"payments\"} |= \"error\"", 0.9, "filter on service label");
        when(this.service.translate(any(NlQueryRequest.class), eq("alice"))).thenReturn(expected);

        this.tester.document("query Translate($p: String!) { "
                        + "nlToLogQL(prompt: $p) { logql confidence explanation } }")
                .variable("p", "errors in payments last 1h")
                .execute()
                .errors()
                .verify()
                .path("nlToLogQL.logql").entity(String.class)
                .isEqualTo("{service=\"payments\"} |= \"error\"")
                .path("nlToLogQL.confidence").entity(Double.class).isEqualTo(0.9)
                .path("nlToLogQL.explanation").entity(String.class).isEqualTo("filter on service label");
    }

    /**
     * Service-layer failure surfaces as a populated GraphQL
     * {@code errors[]} entry rather than a successful data payload.
     */
    @Test
    @WithMockUser(username = "alice")
    void nlToLogQlSurfacesServiceFailureAsGraphQlError() {
        when(this.service.translate(any(NlQueryRequest.class), eq("alice")))
                .thenThrow(new ApplicationException(ErrorCodes.NL_QUERY_INVALID, "logql is blank"));

        this.tester.document("query { nlToLogQL(prompt: \"anything\") { logql } }")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}
