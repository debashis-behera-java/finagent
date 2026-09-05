package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.GuardResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numeric-consistency guard on LLM output (Phase 7).
 *
 * <p>Every <i>significant</i> figure asserted in the prose must be grounded in the
 * FactBundle; ungrounded figures are redacted. "Significant" is deliberately narrow to
 * avoid false positives on ordinary prose counts ("top 3 risks", "5 tools used" must
 * not trip the guard): only decimals, grouped-thousands figures, percentages, currency
 * amounts, and large integers are checked. Plain small integers are ignored, as are
 * calendar years (1900–2100) and the 400-word guidance figure from our own prompt.</p>
 *
 * <p>Matching is numeric (not string): {@code $150.00}, {@code 150}, and {@code 150.0}
 * all ground each other, within a relative tolerance of 1e-6 to absorb formatting
 * rounding.</p>
 */
@Component
@Slf4j
public class SafetyGuard {

    public static final String REDACTION_MARKER = "[redacted: ungrounded figure]";

    private static final double RELATIVE_TOLERANCE = 1e-6;

    /** Decimals, grouped thousands, percents, currency, large integers. */
    private static final Pattern FIGURE = Pattern.compile(
            "[$€£]?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?"      // 1,000 / 1,000,000.50
            + "|[$€£]\\d+(?:\\.\\d+)?"                     // $150 / $150.25
            + "|\\d+\\.\\d+\\s?%"                          // 4.55%
            + "|\\d+\\.\\d+"                               // 150.25
            + "|\\d+\\s%"                                  // 5 %
            + "|\\b\\d{4,}\\b");                           // large integers (years filtered below)

    public GuardResult check(String prose, FactBundle bundle) {
        if (prose == null || prose.isBlank()) {
            throw new IllegalArgumentException("Prose must not be blank");
        }
        if (bundle == null) {
            throw new IllegalArgumentException("FactBundle must not be null");
        }
        Set<BigDecimal> grounded = bundle.groundedNumbers();
        Set<String> ungrounded = new LinkedHashSet<>();

        Matcher matcher = FIGURE.matcher(prose);
        while (matcher.find()) {
            String raw = matcher.group();
            BigDecimal figure = parseFigure(raw);
            if (figure == null || isBenign(figure)) {
                continue;
            }
            if (!isGrounded(figure, grounded)) {
                ungrounded.add(raw.trim());
            }
        }

        List<String> violations = new ArrayList<>(ungrounded);
        if (violations.isEmpty()) {
            return new GuardResult(true, List.of(), prose);
        }
        String redacted = prose;
        for (String raw : violations) {
            redacted = redacted.replace(raw, REDACTION_MARKER);
        }
        log.warn("SafetyGuard redacted {} ungrounded figure(s): {}", violations.size(), violations);
        return new GuardResult(false, violations, redacted);
    }

    private boolean isGrounded(BigDecimal figure, Set<BigDecimal> grounded) {
        for (BigDecimal known : grounded) {
            if (known == null) {
                continue;
            }
            BigDecimal tolerance = known.abs().multiply(BigDecimal.valueOf(RELATIVE_TOLERANCE))
                    .max(BigDecimal.valueOf(1e-9));
            if (figure.subtract(known).abs().compareTo(tolerance) <= 0) {
                return true;
            }
        }
        return false;
    }

    /** Calendar years are prose, not claims about the bundle — never flag them. */
    private boolean isBenign(BigDecimal figure) {
        try {
            int year = figure.intValueExact();
            return figure.scale() <= 0 && year >= 1900 && year <= 2100;
        } catch (ArithmeticException notAnInt) {
            return false;
        }
    }

    private BigDecimal parseFigure(String raw) {
        String cleaned = raw.replaceAll("[$€£,%\\s]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned).stripTrailingZeros();
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
