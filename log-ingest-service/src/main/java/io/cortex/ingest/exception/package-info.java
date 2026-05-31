/**
 * Domain exceptions and the global RFC 7807 problem-detail handler
 * for log-ingest-service. Rule A10: every error body is a
 * {@link org.springframework.http.ProblemDetail} carrying a stable
 * {@code errorCode} extension field.
 */
package io.cortex.ingest.exception;
