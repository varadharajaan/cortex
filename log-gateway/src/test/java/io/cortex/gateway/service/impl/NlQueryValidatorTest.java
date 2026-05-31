package io.cortex.gateway.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.NlQueryResponse;
import io.cortex.gateway.exception.ApplicationException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NlQueryValidator}: exercises every branch of
 * the ADR-0018 output contract.
 */
class NlQueryValidatorTest {

    /** Validator under test. */
    private final NlQueryValidator validator = new NlQueryValidator();

    /** Confidence floor used in most tests (matches application.yml default). */
    private static final double FLOOR = 0.3;

    /** A well-formed response passes through unchanged. */
    @Test
    void validResponsePassesThrough() {
        final NlQueryResponse in = new NlQueryResponse(
                "{service=\"payments\"} |= \"error\"", 0.9, "filter on service label");

        final NlQueryResponse out = this.validator.validate(in, FLOOR);

        assertThat(out).isSameAs(in);
    }

    /** Null response is NL_QUERY_INVALID. */
    @Test
    void nullResponseIsInvalid() {
        assertThatThrownBy(() -> this.validator.validate(null, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** Blank LogQL is NL_QUERY_INVALID. */
    @Test
    void blankLogQlIsInvalid() {
        final NlQueryResponse in = new NlQueryResponse("   ", 0.9, "ok");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** LogQL exceeding the 1024-char cap is NL_QUERY_INVALID. */
    @Test
    void oversizedLogQlIsInvalid() {
        final String huge = "{a=\"b\"} " + "x".repeat(NlQueryValidator.MAX_LOGQL_LENGTH);
        final NlQueryResponse in = new NlQueryResponse(huge, 0.9, "ok");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** Non-ASCII characters in LogQL are NL_QUERY_INVALID. */
    @Test
    void nonAsciiLogQlIsInvalid() {
        final NlQueryResponse in = new NlQueryResponse(
                "{service=\"pay\u00e9ments\"}", 0.9, "ok");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** LogQL not starting with a recognised token is NL_QUERY_INVALID. */
    @Test
    void unrecognisedLeadingTokenIsInvalid() {
        final NlQueryResponse in = new NlQueryResponse(
                "SELECT * FROM logs", 0.9, "ok");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** Confidence outside {@code [0.0, 1.0]} is NL_QUERY_INVALID. */
    @Test
    void confidenceOutOfRangeIsInvalid() {
        final NlQueryResponse over = new NlQueryResponse("{a=\"b\"}", 1.5, "ok");
        final NlQueryResponse under = new NlQueryResponse("{a=\"b\"}", -0.1, "ok");
        final NlQueryResponse nan = new NlQueryResponse("{a=\"b\"}", Double.NaN, "ok");

        for (final NlQueryResponse r : new NlQueryResponse[] {over, under, nan}) {
            assertThatThrownBy(() -> this.validator.validate(r, FLOOR))
                    .isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCode())
                    .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
        }
    }

    /** Confidence below the floor is NL_QUERY_REFUSED. */
    @Test
    void confidenceBelowFloorIsRefused() {
        final NlQueryResponse in = new NlQueryResponse("{a=\"b\"}", 0.05, "ok");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_REFUSED);
    }

    /** Oversized explanation is NL_QUERY_INVALID. */
    @Test
    void oversizedExplanationIsInvalid() {
        final String huge = "x".repeat(NlQueryValidator.MAX_EXPLANATION_LENGTH + 1);
        final NlQueryResponse in = new NlQueryResponse("{a=\"b\"}", 0.9, huge);

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_INVALID);
    }

    /** Refusal marker in the explanation is NL_QUERY_REFUSED. */
    @Test
    void refusalMarkerInExplanationIsRefused() {
        final NlQueryResponse in = new NlQueryResponse(
                "{a=\"b\"}", 0.9, "I'm sorry, I cannot help with that");

        assertThatThrownBy(() -> this.validator.validate(in, FLOOR))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode())
                .isEqualTo(ErrorCodes.NL_QUERY_REFUSED);
    }

    /** count_over_time aggregation passes the leading-token check. */
    @Test
    void countOverTimeAggregationPasses() {
        final NlQueryResponse in = new NlQueryResponse(
                "count_over_time({service=\"pay\"}[5m])", 0.8, "rate of events");

        assertThat(this.validator.validate(in, FLOOR)).isSameAs(in);
    }
}
