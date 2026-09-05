package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.analysis.model.BetaResult;
import com.finagent.analysis.model.MaxDrawdownResult;
import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.analysis.model.RiskCategory;
import com.finagent.analysis.model.RiskScore;
import com.finagent.analysis.model.SharpeRatioResult;
import com.finagent.analysis.model.VolatilityResult;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import com.finagent.news.dto.NewsArticle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactBundleBuilderTest {

    private final FactBundleBuilder builder = new FactBundleBuilder();

    @Test
    void buildsCompleteEntryWithoutGaps() {
        FactEntry entry = builder.buildEntry("AAPL", quote(), fundamentals(),
                history(), articles(), risk());

        assertThat(entry.symbol()).isEqualTo("AAPL");
        assertThat(entry.dataGaps()).isEmpty();
        assertThat(entry.isEmpty()).isFalse();
        assertThat(entry.historyBarCount()).isEqualTo(3);
        assertThat(entry.historyFrom()).isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(entry.historyTo()).isEqualTo(LocalDate.of(2026, 8, 5));
        assertThat(entry.newsCount()).isEqualTo(2);
        assertThat(entry.newsHeadlines()).containsExactly("AAPL hits record high", "Analysts weigh in");
    }

    @Test
    void degradesMissingSourcesIntoGaps() {
        FactEntry entry = builder.buildEntry("AAPL", null, null, null, null, null);

        assertThat(entry.isEmpty()).isTrue();
        assertThat(entry.historyBarCount()).isZero();
        assertThat(entry.newsCount()).isZero();
        assertThat(entry.dataGaps()).containsExactlyInAnyOrder(
                "quote unavailable for AAPL",
                "fundamentals unavailable for AAPL",
                "price history unavailable for AAPL",
                "no news articles found for AAPL",
                "risk analysis unavailable for AAPL");
    }

    @Test
    void rejectsInvalidQuotePrice() {
        Quote bad = new Quote("AAPL", new BigDecimal("-5.00"), BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());

        FactEntry entry = builder.buildEntry("AAPL", bad, null, null, List.of(), null);

        assertThat(entry.quote()).isNull();
        assertThat(entry.dataGaps()).contains("quote price invalid for AAPL");
    }

    @Test
    void skipsBlankHeadlinesButCountsArticles() {
        List<NewsArticle> mixed = List.of(
                new NewsArticle("AAPL", "Real headline", "http://x", "Src", "d", null, Instant.now()),
                new NewsArticle("AAPL", "  ", "http://y", "Src", "d", null, Instant.now()));

        FactEntry entry = builder.buildEntry("AAPL", quote(), null, null, mixed, null);

        assertThat(entry.newsCount()).isEqualTo(2);
        assertThat(entry.newsHeadlines()).containsExactly("Real headline");
    }

    @Test
    void buildsBundleAndExposesGroundedNumbers() {
        FactEntry entry = builder.buildEntry("AAPL", quote(), fundamentals(), history(), articles(), risk());

        FactBundle bundle = builder.buildBundle("Research AAPL", List.of(entry));

        assertThat(bundle.tickers()).containsExactly("AAPL");
        assertThat(bundle.generatedAt()).isNotNull();
        assertThat(bundle.groundedNumbers()).contains(new BigDecimal("150.25").stripTrailingZeros());
        assertThat(bundle.toPromptText()).contains("AAPL").contains("150.25").contains("Technology");
    }

    @Test
    void promptTextMarksUnavailableSources() {
        FactEntry entry = builder.buildEntry("AAPL", null, null, null, null, null);

        FactBundle bundle = builder.buildBundle("Research AAPL", List.of(entry));

        assertThat(bundle.toPromptText()).contains("unavailable");
    }

    @Test
    void rejectsBlankRequestAndEmptyEntries() {
        FactEntry entry = builder.buildEntry("AAPL", quote(), null, null, List.of(), null);
        assertThatThrownBy(() -> builder.buildBundle("  ", List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.buildBundle("Research", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.buildEntry("  ", quote(), null, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Quote quote() {
        return new Quote("AAPL", new BigDecimal("150.25"), new BigDecimal("2.50"),
                new BigDecimal("1.69"), Instant.now());
    }

    private static Fundamentals fundamentals() {
        return new Fundamentals("AAPL", "AAPL Corp.", "Technology", "Consumer Electronics",
                new BigDecimal("2500000000000"), new BigDecimal("28.5"),
                new BigDecimal("0.55"), new BigDecimal("5.27"), "Stub");
    }

    private static HistoricalSeries history() {
        return new HistoricalSeries("AAPL", List.of(
                bar(LocalDate.of(2026, 8, 3), "148.00"),
                bar(LocalDate.of(2026, 8, 4), "149.50"),
                bar(LocalDate.of(2026, 8, 5), "150.25")));
    }

    private static HistoricalBar bar(LocalDate date, String close) {
        BigDecimal c = new BigDecimal(close);
        return new HistoricalBar(date, c, c, c, c, 1_000_000L);
    }

    private static List<NewsArticle> articles() {
        return List.of(
                new NewsArticle("AAPL", "AAPL hits record high", "http://a", "Wire", "d", null, Instant.now()),
                new NewsArticle("AAPL", "Analysts weigh in", "http://b", "Wire", "d", null, Instant.now()));
    }

    private static RiskAnalysisResult risk() {
        return new RiskAnalysisResult("AAPL", LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5), 3,
                VolatilityResult.of(0.21),
                MaxDrawdownResult.unavailable("Need at least 2 closing prices"),
                BetaResult.of(1.15, "SPY"),
                SharpeRatioResult.unavailable("Risk-free rate not configured"),
                RiskScore.of(42.5, RiskCategory.MODERATE, Map.of("volatility", 0.7), List.of("explanation")));
    }
}
