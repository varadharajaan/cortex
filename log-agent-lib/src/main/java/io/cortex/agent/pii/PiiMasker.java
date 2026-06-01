package io.cortex.agent.pii;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort PII redactor reused on the agent (client-side
 * pre-ship hook) and on the log-ingest-service (server-side trust
 * boundary; ADR-0023 / spec Sec 5.3 / LD4).
 *
 * <p>Patterns covered, in scan order:</p>
 * <ol>
 *   <li>Email address -- replaced with {@code <email>}.</li>
 *   <li>JSON Web Token -- three base64url segments separated by
 *       dots, starting with the canonical {@code eyJ} header --
 *       replaced with {@code <jwt>}.</li>
 *   <li>AWS access key id ({@code AKIA[A-Z0-9]{16}}) -- replaced
 *       with {@code <aws-key>}.</li>
 *   <li>16-digit credit-card number in {@code dddd dddd dddd dddd},
 *       {@code dddd-dddd-dddd-dddd}, or bare 16-digit format --
 *       replaced with {@code <cc>}.</li>
 *   <li>US SSN in {@code ddd-dd-dddd} format -- replaced with
 *       {@code <ssn>}.</li>
 * </ol>
 *
 * <p>Output of one rule feeds the next so a single substring is
 * never double-counted. The replacement tokens contain no digits
 * and no {@code @}, so no rule can match its own output.</p>
 *
 * <p>This is a defence-in-depth layer, not a full DLP solution.
 * Any production deployment should fail closed and route truly
 * sensitive fields through structured channels rather than
 * free-text log messages.</p>
 */
public final class PiiMasker {

    /** Replacement token for email matches. */
    private static final String REPL_EMAIL = "<email>";

    /** Replacement token for JWT matches. */
    private static final String REPL_JWT = "<jwt>";

    /** Replacement token for AWS access key matches. */
    private static final String REPL_AWS_KEY = "<aws-key>";

    /** Replacement token for credit-card matches. */
    private static final String REPL_CC = "<cc>";

    /** Replacement token for SSN matches. */
    private static final String REPL_SSN = "<ssn>";

    /**
     * Compiled rules applied in declaration order. Output of rule
     * {@code N} is the input of rule {@code N + 1}.
     */
    private static final List<MaskRule> RULES = List.of(
            new MaskRule(
                    Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+"),
                    REPL_EMAIL),
            new MaskRule(
                    Pattern.compile(
                            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
                    REPL_JWT),
            new MaskRule(
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    REPL_AWS_KEY),
            new MaskRule(
                    Pattern.compile("(?<!\\d)(?:\\d{4}[ -]?){3}\\d{4}(?!\\d)"),
                    REPL_CC),
            new MaskRule(
                    Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)"),
                    REPL_SSN));

    /** Static helper; never instantiated. */
    private PiiMasker() {
        // no instances
    }

    /**
     * Masks every PII pattern in {@code input} and reports how
     * many substitutions were performed.
     *
     * <p>{@code null} and empty inputs are returned unchanged with
     * {@code appliedCount = 0}. Inputs containing no PII match
     * round-trip the same {@link String} reference.</p>
     *
     * @param input free-text input that may contain PII; may be
     *              {@code null}
     * @return immutable {@link MaskResult} carrying the masked text
     *         and the total number of substitutions across all
     *         rules
     */
    public static MaskResult mask(final String input) {
        if (input == null || input.isEmpty()) {
            return new MaskResult(input, 0);
        }
        String text = input;
        int applied = 0;
        for (final MaskRule rule : RULES) {
            final RulePass pass = applyRule(text, rule);
            text = pass.text();
            applied += pass.applied();
        }
        return new MaskResult(text, applied);
    }

    /**
     * Applies a single {@link MaskRule} to {@code text}, returning
     * the rewritten text and the count of replacements made.
     *
     * @param text source text
     * @param rule compiled pattern + replacement token
     * @return rewritten text and per-rule replacement count
     */
    private static RulePass applyRule(final String text,
                                      final MaskRule rule) {
        final Matcher matcher = rule.pattern().matcher(text);
        if (!matcher.find()) {
            return new RulePass(text, 0);
        }
        final StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        int count = 0;
        do {
            sb.append(text, last, matcher.start())
                    .append(rule.replacement());
            last = matcher.end();
            count++;
        } while (matcher.find());
        sb.append(text, last, text.length());
        return new RulePass(sb.toString(), count);
    }

    /**
     * Compiled pattern paired with its replacement token.
     *
     * @param pattern     compiled regex; never {@code null}
     * @param replacement literal replacement text; never {@code null}
     */
    private record MaskRule(Pattern pattern, String replacement) {
    }

    /**
     * Per-rule intermediate result returned by
     * {@link #applyRule(String, MaskRule)}.
     *
     * @param text    rewritten text after this rule
     * @param applied number of substitutions this rule performed
     */
    private record RulePass(String text, int applied) {
    }
}
