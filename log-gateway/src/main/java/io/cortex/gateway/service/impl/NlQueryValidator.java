package io.cortex.gateway.service.impl;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates a model-returned {@link NlQueryResponse} against the contract
 * locked in ADR-0018 (P3.3). Throws {@link ApplicationException} with the
 * appropriate {@link ErrorCodes} value when a field is malformed or a
 * refusal marker is present; returns the response unchanged otherwise.
 */
@Component
public final class NlQueryValidator {

    /** Maximum allowed LogQL string length. */
    static final int MAX_LOGQL_LENGTH = 1024;

    /** Maximum allowed explanation string length. */
    static final int MAX_EXPLANATION_LENGTH = 2048;

    /** ASCII-printable + LogQL whitespace pattern (rule A0.1 + ADR-0018). */
    private static final Pattern LOGQL_CHARSET = Pattern.compile("^[\\x20-\\x7E\\t\\n\\r]+$");

    /** Markers that indicate the model refused to answer (ADR-0018). */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "i refuse",
            "i cannot answer",
            "i can't answer",
            "i'm sorry",
            "as an ai language model",
            "i am not able");

    /** Allowed LogQL leading tokens (stream selector or scalar aggregation). */
    private static final List<String> ALLOWED_LEADING_TOKENS = List.of(
            "{",
            "count_over_time",
            "rate",
            "sum",
            "avg",
            "max",
            "min",
            "topk",
            "bottomk");

    /** Confidence floor injected by the impl; passed per-call to keep the validator stateless. */
    private static final double CONFIDENCE_LOWER_BOUND = 0.0;

    /** Confidence ceiling. */
    private static final double CONFIDENCE_UPPER_BOUND = 1.0;

    /**
     * Validates a parsed response. Throws on the first violation found so
     * the global exception handler surfaces a single coherent failure.
     *
     * @param response        parsed model response
     * @param confidenceFloor minimum acceptable confidence (NL_QUERY_REFUSED below this)
     * @return the same {@code response} when valid
     * @throws ApplicationException with NL_QUERY_INVALID / NL_QUERY_REFUSED on failure
     */
    public NlQueryResponse validate(final NlQueryResponse response, final double confidenceFloor) {
        if (response == null) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID, "null response");
        }
        validateLogQl(response.logql());
        validateConfidence(response.confidence(), confidenceFloor);
        validateExplanation(response.explanation());
        return response;
    }

    /**
     * Asserts the LogQL string is non-blank, length-bounded, ASCII, and
     * starts with one of the allowed leading tokens.
     *
     * @param logql parsed LogQL
     * @throws ApplicationException carrying NL_QUERY_INVALID when the LogQL fails any check
     */
    private static void validateLogQl(final String logql) {
        if (logql == null || logql.isBlank()) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID, "logql is blank");
        }
        if (logql.length() > MAX_LOGQL_LENGTH) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID,
                    "logql exceeds max length " + MAX_LOGQL_LENGTH);
        }
        if (!LOGQL_CHARSET.matcher(logql).matches()) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID, "logql contains non-ASCII characters");
        }
        final String trimmed = logql.stripLeading();
        boolean ok = false;
        for (final String token : ALLOWED_LEADING_TOKENS) {
            if (trimmed.startsWith(token)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID,
                    "logql does not start with a recognised LogQL token");
        }
    }

    /**
     * Asserts confidence is in {@code [0.0, 1.0]} and surfaces a refusal
     * when it falls below {@code confidenceFloor}.
     *
     * @param confidence      model-reported confidence
     * @param confidenceFloor minimum acceptable confidence
     * @throws ApplicationException with NL_QUERY_INVALID (out of range) or NL_QUERY_REFUSED (below floor)
     */
    private static void validateConfidence(final double confidence, final double confidenceFloor) {
        if (Double.isNaN(confidence) || confidence < CONFIDENCE_LOWER_BOUND || confidence > CONFIDENCE_UPPER_BOUND) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID,
                    "confidence must be within [0.0, 1.0]");
        }
        if (confidence < confidenceFloor) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_REFUSED,
                    "model confidence " + confidence + " below floor " + confidenceFloor);
        }
    }

    /**
     * Asserts the explanation is length-bounded and contains no refusal
     * marker. A bounded but refusing explanation maps to NL_QUERY_REFUSED.
     *
     * @param explanation parsed explanation
     * @throws ApplicationException with NL_QUERY_INVALID (bad shape) or NL_QUERY_REFUSED (refusal marker)
     */
    private static void validateExplanation(final String explanation) {
        if (explanation == null) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID, "explanation is null");
        }
        if (explanation.length() > MAX_EXPLANATION_LENGTH) {
            throw new ApplicationException(ErrorCodes.NL_QUERY_INVALID,
                    "explanation exceeds max length " + MAX_EXPLANATION_LENGTH);
        }
        final String lower = explanation.toLowerCase(java.util.Locale.ROOT);
        for (final String marker : REFUSAL_MARKERS) {
            if (lower.contains(marker)) {
                throw new ApplicationException(ErrorCodes.NL_QUERY_REFUSED,
                        "explanation contains refusal marker: " + marker);
            }
        }
    }
}
