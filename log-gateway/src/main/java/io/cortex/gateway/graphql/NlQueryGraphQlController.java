package io.cortex.gateway.graphql;

import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.NlQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/**
 * GraphQL resolver for the {@code nlToLogQL} root query (P9.0 / ADR-0049).
 *
 * <p>Mirrors the REST endpoint {@link ApiPaths#QUERY_NL} (P3.3 /
 * ADR-0018) by delegating to the same {@link NlQueryService}
 * implementation. The two surfaces (REST and GraphQL) therefore
 * share validation, refusal mapping, upstream-failure mapping, and
 * sub-bucket enforcement at the service layer; the only divergence
 * is the transport layer.</p>
 *
 * <p>Conditional on {@code cortex.gateway.nl-query.enabled=true} so a
 * deploy that wants the gateway without the NL feature can disable it
 * at the property layer; in that case the resolver bean is not
 * registered and the GraphQL schema-validation layer rejects
 * {@code nlToLogQL} queries with {@code DataFetchingException}.</p>
 *
 * <p><strong>Rate-limit posture</strong>: the global
 * {@code RateLimitFilter} already covers {@link ApiPaths#GRAPHQL} so
 * authenticated callers share their global bucket between REST and
 * GraphQL. The per-feature NL sub-bucket
 * ({@code @RateLimitFeature("nl-query")}) only fires on Spring MVC
 * controllers because
 * {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}
 * is a {@code HandlerInterceptor} that scans the matched
 * {@code HandlerMethod}; the GraphQL dispatcher routes through a
 * single {@code GraphQlHttpHandler} so resolver methods are not
 * visible. Porting the NL sub-bucket to the GraphQL path is queued
 * as P9.0a -- structural gap, not a regression.</p>
 */
@Controller
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.nl-query", name = "enabled", havingValue = "true")
public class NlQueryGraphQlController {

    /** Delegated NL-to-LogQL translation logic (shared with REST). */
    private final NlQueryService service;

    /**
     * Translates the supplied prompt to a structured LogQL result.
     *
     * @param prompt caller-supplied natural-language prompt; the
     *               {@code !} on the schema field guarantees non-null
     *               at this point (Spring for GraphQL rejects missing
     *               required arguments before the resolver runs)
     * @return the structured LogQL result; payload-identical to the
     *         REST surface
     * @throws ApplicationException when the request has no
     *                              authenticated principal (mirrors
     *                              REST behaviour)
     */
    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public NlQueryResponse nlToLogQL(@Argument final String prompt) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "missing authentication");
        }
        return this.service.translate(new NlQueryRequest(prompt), authentication.getName());
    }
}
