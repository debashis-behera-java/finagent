package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.model.GuardResult;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.Quote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafetyGuardTest {

    private final SafetyGuard guard = new SafetyGuard();

    private static FactBundle bundle() {
        Quote quote = new Quote("AAPL", new BigDecimal("150.25"), new BigDecimal("2.50"),
                new BigDecimal("1.69"), Instant.now());
        Fundamentals fundamentals = new Fundamentals("AAPL", "AAPL Corp.", "Technology",
                "Consumer Electronics", new BigDecimal("2500000000000"), new BigDecimal("28.5"),
                new BigDecimal("0.55"), new BigDecimal("5.27"), "Stub");
        FactEntry entry = new FactEntry("AAPL", quote, fundamentals, 21, null, null,
                3, List.of("headline"), null, null, List.of());
        return new FactBundle("Research AAPL", List.of("AAPL"), Instant.now(), List.of(entry));
    }

    @Test
    void passesGroundedFiguresInAnyFormatting() {
        String prose = "AAPL trades at $150.25, up 1.69%, with a P/E of 28.5 across 21 bars. "
                + "This is educational research, not financial advice.";

        GuardResult result = guard.check(prose, bundle());

        assertThat(result.passed()).isTrue();
        assertThat(result.ungroundedFigures()).isEmpty();
        assertThat(result.redactedText()).isEqualTo(prose);
    }

    @Test
    void flagsAndRedactsFabricatedFigure() {
        String prose = "AAPL trades at 150.25 but our price target is 999.99. "
                + "This is educational research, not financial advice.";

        GuardResult result = guard.check(prose, bundle());

        assertThat(result.passed()).isFalse();
        assertThat(result.ungroundedFigures()).containsExactly("999.99");
        assertThat(result.redactedText())
                .contains(SafetyGuard.REDACTION_MARKER)
                .contains("150.25")
                .doesNotContain("999.99");
    }

    @Test
    void ignoresProseCountsYearsAndSmallIntegers() {
        String prose = "Top 3 risks across 2 sectors since 2020 show 5 themes. "
                + "This is educational research, not financial advice.";

        assertThat(guard.check(prose, bundle()).passed()).isTrue();
    }

    @Test
    void rejectsBlankProseAndNullBundle() {
        assertThatThrownBy(() -> guard.check("  ", bundle()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.check("Some prose", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
