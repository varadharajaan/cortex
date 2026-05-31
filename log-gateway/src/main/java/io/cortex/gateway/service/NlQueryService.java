package io.cortex.gateway.service;

import io.cortex.gateway.dto.request.NlQueryRequest;
import io.cortex.gateway.dto.response.NlQueryResponse;

/**
 * Translates a natural-language prompt into a structured LogQL query
 * (B20.1, P3.3 / ADR-0018).
 *
 * <p>Implementations delegate to a Spring AI {@code ChatClient}, validate
 * the model output against the contract defined in ADR-0018, and enforce
 * the per-principal NL sub-bucket BEFORE the LLM call (so a rate-limited
 * caller does not consume LLM tokens).</p>
 */
public interface NlQueryService {

    /**
     * Translates {@code request.prompt()} to LogQL on behalf of
     * {@code principalName}.
     *
     * @param request       caller-supplied NL prompt; must not be {@code null}
     * @param principalName authenticated principal for sub-bucket keying;
     *                      must not be blank
     * @return the structured LogQL response
     */
    NlQueryResponse translate(NlQueryRequest request, String principalName);
}
