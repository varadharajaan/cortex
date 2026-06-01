package io.cortex.agent.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PiiMasker} covering every rule plus the
 * null / empty / no-match branches required for the 80% line +
 * branch JaCoCo gate on log-agent-lib.
 */
class PiiMaskerTest {

    /** Null inputs must short-circuit to a null result with zero count. */
    @Test
    @DisplayName("null input returns null with zero applied count")
    void nullInputReturnsNullWithZeroCount() {
        final MaskResult result = PiiMasker.mask(null);
        assertThat(result.text()).isNull();
        assertThat(result.appliedCount()).isZero();
    }

    /** Empty inputs must short-circuit to an empty result with zero count. */
    @Test
    @DisplayName("empty input returns empty with zero applied count")
    void emptyInputReturnsEmptyWithZeroCount() {
        final MaskResult result = PiiMasker.mask("");
        assertThat(result.text()).isEmpty();
        assertThat(result.appliedCount()).isZero();
    }

    /** Inputs containing no recognised PII must round-trip unchanged. */
    @Test
    @DisplayName("input with no PII round-trips unchanged")
    void noPiiReturnsInputUnchanged() {
        final String input = "user logged in successfully from session 42";
        final MaskResult result = PiiMasker.mask(input);
        assertThat(result.text()).isEqualTo(input);
        assertThat(result.appliedCount()).isZero();
    }

    /** Single email match must be replaced with the email token. */
    @Test
    @DisplayName("email address is replaced with <email>")
    void emailIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "contact alice.smith+test@example.co.uk for details");
        assertThat(result.text())
                .isEqualTo("contact <email> for details");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** Each email match must count and replace independently. */
    @Test
    @DisplayName("multiple emails in one string each count as one match")
    void multipleEmailsAreMasked() {
        final MaskResult result = PiiMasker.mask(
                "from a@x.com to b@y.org via c@z.io");
        assertThat(result.text())
                .isEqualTo("from <email> to <email> via <email>");
        assertThat(result.appliedCount()).isEqualTo(3);
    }

    /** Canonical eyJ-prefixed three-segment JWT must be masked. */
    @Test
    @DisplayName("JWT bearer payload is replaced with <jwt>")
    void jwtIsMasked() {
        final String jwt =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                        + ".eyJzdWIiOiJ1c2VyMSJ9"
                        + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        final MaskResult result = PiiMasker.mask(
                "Authorization: Bearer " + jwt + " done");
        assertThat(result.text())
                .isEqualTo("Authorization: Bearer <jwt> done");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** AKIA-prefixed 20-char AWS access key id must be masked. */
    @Test
    @DisplayName("AWS access key id is replaced with <aws-key>")
    void awsAccessKeyIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "key=AKIAIOSFODNN7EXAMPLE leaked");
        assertThat(result.text()).isEqualTo("key=<aws-key> leaked");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** 4x4 space-separated credit-card number must be masked. */
    @Test
    @DisplayName("credit card in space-separated format is masked")
    void creditCardSpaceFormatIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "card 4111 1111 1111 1111 declined");
        assertThat(result.text()).isEqualTo("card <cc> declined");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** 4x4 dash-separated credit-card number must be masked. */
    @Test
    @DisplayName("credit card in dash-separated format is masked")
    void creditCardDashFormatIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "card 4111-1111-1111-1111 declined");
        assertThat(result.text()).isEqualTo("card <cc> declined");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** Bare 16-digit credit-card number must be masked. */
    @Test
    @DisplayName("credit card in bare 16-digit format is masked")
    void creditCardBareFormatIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "card 4111111111111111 declined");
        assertThat(result.text()).isEqualTo("card <cc> declined");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** US SSN in ddd-dd-dddd format must be masked. */
    @Test
    @DisplayName("US SSN in ddd-dd-dddd format is masked")
    void ssnIsMasked() {
        final MaskResult result = PiiMasker.mask(
                "ssn 123-45-6789 on file");
        assertThat(result.text()).isEqualTo("ssn <ssn> on file");
        assertThat(result.appliedCount()).isEqualTo(1);
    }

    /** All five rules must fire on one mixed input with summed count. */
    @Test
    @DisplayName("all rules fire on one mixed string with correct count")
    void multiplePatternsInOneStringAreAllMasked() {
        final String jwt =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                        + ".eyJzdWIiOiJ1c2VyMSJ9"
                        + ".dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        final String input = "u=alice@example.com jwt=" + jwt
                + " aws=AKIAIOSFODNN7EXAMPLE cc=4111-1111-1111-1111"
                + " ssn=123-45-6789";
        final MaskResult result = PiiMasker.mask(input);
        assertThat(result.text())
                .isEqualTo("u=<email> jwt=<jwt> aws=<aws-key> cc=<cc>"
                        + " ssn=<ssn>");
        assertThat(result.appliedCount()).isEqualTo(5);
    }

    /** Re-masking an already-masked output must be idempotent. */
    @Test
    @DisplayName("masking the already-masked output is idempotent")
    void consecutiveCallsAreIdempotent() {
        final MaskResult first = PiiMasker.mask(
                "u=alice@example.com ssn=123-45-6789");
        final MaskResult second = PiiMasker.mask(first.text());
        assertThat(second.text()).isEqualTo(first.text());
        assertThat(second.appliedCount()).isZero();
    }

    /** 17-digit number must not be masked as a credit card (boundary). */
    @Test
    @DisplayName("17-digit number adjacent to text is not masked as a credit card")
    void seventeenDigitsAreNotMaskedAsCreditCard() {
        final MaskResult result = PiiMasker.mask("id=12345678901234567 ok");
        assertThat(result.text()).isEqualTo("id=12345678901234567 ok");
        assertThat(result.appliedCount()).isZero();
    }
}
