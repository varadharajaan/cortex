package io.cortex.agent.pii;

/**
 * Output of {@link PiiMasker#mask(String)}: the rewritten text plus
 * the total number of PII substitutions that were performed.
 *
 * <p>{@code appliedCount} is the sum across all rules in the masker
 * pipeline; callers should treat any non-zero value as "this entry
 * contained at least one PII match" when emitting metrics or audit
 * trails.</p>
 *
 * @param text         masked output; identical reference to the input
 *                     when no PII was found; {@code null} only when
 *                     the input was {@code null}
 * @param appliedCount non-negative count of substitutions; {@code 0}
 *                     means the masker did not modify the input
 */
public record MaskResult(String text, int appliedCount) {
}
