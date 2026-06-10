package io.cortex.remediation.anomaly;

/**
 * Minimal JSON error body for the direct anomaly query endpoint.
 *
 * @param errorCode stable machine-readable error code
 * @param message   short human-readable validation message
 */
public record AnomalyErrorResponse(String errorCode, String message) {
}
