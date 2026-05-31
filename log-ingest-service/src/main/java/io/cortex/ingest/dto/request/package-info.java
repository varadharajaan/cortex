/**
 * Inbound request DTOs for log-ingest-service. All DTOs are
 * immutable records (rule 8.4) and carry Bean Validation annotations
 * so {@link org.springframework.validation.annotation.Validated}
 * controllers surface invariants as RFC 7807 400 problems via
 * {@link io.cortex.ingest.exception.GlobalExceptionHandler}.
 */
package io.cortex.ingest.dto.request;
