package io.cortex.gateway.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Natural-language query request body for {@code POST /api/v1/query/nl}
 * (B20.1, P3.3 / ADR-0018).
 *
 * <p>The {@code prompt} is a free-form English description of a log query
 * (e.g. {@code "errors in payment service last 1h"}); the gateway delegates
 * to a Spring AI {@link org.springframework.ai.chat.client.ChatClient
 * ChatClient} to translate it into a structured LogQL response.</p>
 *
 * <p>The {@link Size} cap of 2048 chars is a deliberate prompt-injection
 * defence (ADR-0018): bounded input limits how much hostile data can be
 * embedded in the prompt template before the system instructions are
 * pushed out of context.</p>
 *
 * @param prompt caller-supplied natural-language query; must not be blank
 */
public record NlQueryRequest(
        @NotBlank @Size(max = 2048) String prompt) {
}
