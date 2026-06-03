package io.cortex.processor.classify;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Internal record mirroring the JSON shape we ask the model to
 * produce (P5.2 / ADR-0029).
 *
 * <p>Kept separate from the SPI {@link Classification} record so the
 * wire schema we negotiate with the LLM can evolve independently of
 * the SPI return type. {@link SpringAiAnomalyClassifier} parses the
 * model output into this record, applies the confidence-threshold
 * gate, and then derives the public {@link Classification} value
 * handed to the consumer.</p>
 *
 * <p>Mirror of the gateway P3.3 / ADR-0018
 * {@code NlQueryServiceImpl.ModelResponse} pattern.</p>
 *
 * @param anomaly    {@code true} iff the model judges the event to
 *                   be an anomaly
 * @param severity   severity bucket; one of {@code LOW}, {@code MEDIUM},
 *                   {@code HIGH}, {@code CRITICAL}, {@code NONE}.
 *                   Models that emit lower-case strings are
 *                   normalised inside the classifier.
 * @param reason     short human-readable explanation; truncated
 *                   inside the classifier to honour the prompt
 *                   contract (max 256 chars)
 * @param confidence model-reported confidence in {@code [0.0, 1.0]};
 *                   classifier downgrades verdicts below
 *                   {@link ClassifierProperties#confidenceThreshold()}
 *                   to {@link Classification#none()}
 */
public record ModelClassification(
        boolean anomaly,
        String severity,
        String reason,
        double confidence) {

    /**
     * Jackson constructor with null-tolerant defaults so a model
     * that omits an optional field does not blow up the parser.
     *
     * @param anomaly    see record-level Javadoc
     * @param severity   see record-level Javadoc
     * @param reason     see record-level Javadoc
     * @param confidence see record-level Javadoc
     */
    @JsonCreator
    public ModelClassification(
            @JsonProperty("anomaly") final boolean anomaly,
            @JsonProperty("severity") final String severity,
            @JsonProperty("reason") final String reason,
            @JsonProperty("confidence") final Double confidence) {
        this(anomaly,
                severity == null ? "NONE" : severity,
                reason == null ? "" : reason,
                confidence == null ? 0.0d : confidence);
    }
}
