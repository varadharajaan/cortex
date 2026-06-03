package io.cortex.processor.classify;

/**
 * Immutable verdict returned by an {@link AnomalyClassifier} for a
 * single CloudEvent (P5.0 / ADR-0028 D1).
 *
 * <p>{@link #none()} is the singleton "no anomaly" verdict and is
 * the only value the {@link NoopAnomalyClassifier} returns. Real
 * classifiers (P5.2 Spring AI) return non-none verdicts whose
 * {@link #severity()} drives the P5.4
 * {@code cortex.anomalies.v1} publish path that feeds the P6
 * remediation queue.</p>
 *
 * @param anomaly   {@code true} iff this event is classified as an
 *                  anomaly (the {@link #none()} singleton has
 *                  {@code anomaly=false}).
 * @param severity  severity bucket for the anomaly (P6 SLO routing).
 *                  {@code "NONE"} on the {@link #none()} singleton.
 * @param reason    short human-readable explanation included on the
 *                  P5.4 cortex.anomalies.v1 envelope. Empty string
 *                  on the {@link #none()} singleton.
 */
public record Classification(boolean anomaly, String severity, String reason) {

    /**
     * The singleton "no anomaly" verdict returned by
     * {@link NoopAnomalyClassifier} and any real classifier that
     * decides the event is benign.
     */
    private static final Classification NONE =
            new Classification(false, "NONE", "");

    /**
     * Get the singleton "no anomaly" verdict.
     *
     * @return the {@link #NONE} singleton
     */
    public static Classification none() {
        return NONE;
    }
}
