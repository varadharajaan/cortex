package io.cortex.agent.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CortexClientException}.
 */
class CortexClientExceptionTest {

    /** Message-only constructor exposes the message and a {@code null} cause. */
    @Test
    void messageOnlyConstructor() {
        final CortexClientException ex = new CortexClientException("boom");
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    /** Message-and-cause constructor exposes both. */
    @Test
    void messageAndCauseConstructor() {
        final Throwable cause = new IllegalStateException("inner");
        final CortexClientException ex = new CortexClientException("boom", cause);
        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
