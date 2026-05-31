package io.cortex.gateway.controller;

import io.cortex.gateway.annotation.RateLimitFeature;
import io.cortex.gateway.constants.ApiPaths;
import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.service.NlQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Natural-language query endpoint (B20.1, P3.3 / ADR-0018).
 *
 * <p>{@code POST /api/v1/query/nl} accepts a free-form prompt and
 * returns a structured LogQL response. Authentication is required
 * (the global SecurityConfig blocks every endpoint except the public
 * health and auth paths); a per-principal NL sub-bucket adds a
 * second rate-limit layer on top of P3.2's global bucket.</p>
 *
 * <p>Conditional on {@code cortex.gateway.nl-query.enabled=true} so a
 * deploy that wants the gateway without the NL feature can disable
 * it at the property layer; in that case the controller bean is not
 * registered and the path returns 404.</p>
 */
@RestController
@RequestMapping(ApiPaths.QUERY_NL)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cortex.gateway.nl-query", name = "enabled", havingValue = "true")
public class NlQueryController {

    /** Delegated NL-to-LogQL translation logic. */
    private final NlQueryService service;

    /**
     * Translates the supplied prompt to a structured LogQL response.
     *
     * <p>The {@link RateLimitFeature @RateLimitFeature} annotation
     * enforces a per-principal NL sub-bucket BEFORE the controller body
     * runs; on exhaustion the
     * {@link io.cortex.gateway.interceptor.RateLimitFeatureInterceptor}
     * throws a {@link io.cortex.gateway.exception.RateLimitedException}
     * carrying {@link ErrorCodes#NL_QUERY_RATE_LIMITED} so the LLM call
     * is never made and the 429 body's {@code errorCode} is distinct
     * from the global {@code RATE_LIMITED} code (ADR-0018 section 3 +
     * ADR-0021).</p>
     *
     * @param request validated NL prompt body
     * @return HTTP 200 with the structured response
     * @throws ApplicationException when the request has no authenticated principal
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @RateLimitFeature(
            name = "nl-query",
            capacity = "${cortex.gateway.nl-query.sub-bucket-capacity:10}",
            refill = "${cortex.gateway.nl-query.sub-bucket-refill-period:PT1M}",
            errorCode = "NL_QUERY_RATE_LIMITED",
            keyPrefix = "${cortex.gateway.nl-query.sub-bucket-key-prefix:cortex:rl:nlq:}")
    public ResponseEntity<NlQueryResponse> translate(@Valid @RequestBody final NlQueryRequest request) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ApplicationException(ErrorCodes.UNAUTHENTICATED, "missing authentication");
        }
        return ResponseEntity.ok(this.service.translate(request, authentication.getName()));
    }
}
