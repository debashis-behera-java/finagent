package com.finagent.agent.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable snapshot of all validated facts for one research run — the <b>single source
 * of truth</b> for both the LLM ({@code InterpretationSynthesizer} receives this as
 * structured input and must produce prose only) and downstream consumers (Phase 10 PDF
 * renders numbers exclusively from here, never by parsing LLM prose).
 */
public record FactBundle(
        String requestText,
        List<String> tickers,
        Instant generatedAt,
        List<FactEntry> entries) {

    public FactBundle {
        tickers = List.copyOf(tickers);
        entries = List.copyOf(entries);
    }

    /**
     * Every numeric figure in the bundle, for the {@code SafetyGuard} numeric-consistency
     * check. Includes prices, changes, fundamentals, risk metrics, composite score,
     * sentiment score and sentiment counts, and bar/news counts.
     */
    public Set<BigDecimal> groundedNumbers() {
        Set<BigDecimal> numbers = new LinkedHashSet<>();
        for (FactEntry entry : entries) {
            if (entry.quote() != null) {
                addIfNotNull(numbers, entry.quote().price());
                addIfNotNull(numbers, entry.quote().change());
                addIfNotNull(numbers, entry.quote().changePercent());
            }
            if (entry.fundamentals() != null) {
                addIfNotNull(numbers, entry.fundamentals().marketCap());
                addIfNotNull(numbers, entry.fundamentals().peRatio());
                addIfNotNull(numbers, entry.fundamentals().dividendYield());
                addIfNotNull(numbers, entry.fundamentals().eps());
            }
            numbers.add(BigDecimal.valueOf(entry.historyBarCount()));
            numbers.add(BigDecimal.valueOf(entry.newsCount()));
            if (entry.newsSentiment() != null && entry.newsSentiment().isAvailable()) {
                addIfNotNull(numbers, toBigDecimal(entry.newsSentiment().score()));
                addIfNotNull(numbers, toBigDecimal(entry.newsSentiment().confidence()));
                numbers.add(BigDecimal.valueOf(entry.newsSentiment().analyzedCount()));
                numbers.add(BigDecimal.valueOf(entry.newsSentiment().positiveCount()));
                numbers.add(BigDecimal.valueOf(entry.newsSentiment().neutralCount()));
                numbers.add(BigDecimal.valueOf(entry.newsSentiment().negativeCount()));
            }
            if (entry.riskAnalysis() != null) {
                var risk = entry.riskAnalysis();
                if (risk.volatility() != null && risk.volatility().available()) {
                    addIfNotNull(numbers, toBigDecimal(risk.volatility().annualizedVolatility()));
                }
                if (risk.maxDrawdown() != null && risk.maxDrawdown().available()) {
                    addIfNotNull(numbers, toBigDecimal(risk.maxDrawdown().maxDrawdown()));
                }
                if (risk.beta() != null && risk.beta().available()) {
                    addIfNotNull(numbers, toBigDecimal(risk.beta().beta()));
                }
                if (risk.sharpe() != null && risk.sharpe().available()) {
                    addIfNotNull(numbers, toBigDecimal(risk.sharpe().sharpeRatio()));
                }
                if (risk.riskScore() != null && risk.riskScore().available()) {
                    addIfNotNull(numbers, toBigDecimal(risk.riskScore().score()));
                }
            }
        }
        return numbers;
    }

    /** Structured rendering of the bundle for the LLM user prompt (facts in, prose out). */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Research request: ").append(requestText == null ? "" : requestText).append("\n");
        for (FactEntry entry : entries) {
            sb.append("\n=== ").append(entry.symbol()).append(" ===\n");
            if (entry.quote() != null) {
                sb.append("Price: ").append(entry.quote().price())
                        .append(" (change ").append(entry.quote().change())
                        .append(", ").append(entry.quote().changePercent()).append("%)\n");
            } else {
                sb.append("Price: unavailable\n");
            }
            if (entry.fundamentals() != null) {
                var f = entry.fundamentals();
                sb.append("Fundamentals: ").append(f.companyName())
                        .append("; sector=").append(f.sector())
                        .append("; industry=").append(f.industry())
                        .append("; marketCap=").append(f.marketCap())
                        .append("; peRatio=").append(f.peRatio())
                        .append("; dividendYield=").append(f.dividendYield())
                        .append("; eps=").append(f.eps()).append("\n");
            } else {
                sb.append("Fundamentals: unavailable\n");
            }
            sb.append("History bars: ").append(entry.historyBarCount());
            if (entry.historyFrom() != null) {
                sb.append(" [").append(entry.historyFrom()).append(" .. ").append(entry.historyTo()).append("]");
            }
            sb.append("\n");
            sb.append("News articles: ").append(entry.newsCount()).append("\n");
            for (String headline : entry.newsHeadlines()) {
                sb.append("- ").append(headline).append("\n");
            }
            sb.append("News sentiment: ").append(describe(entry.newsSentiment())).append("\n");
            if (entry.riskAnalysis() != null) {
                var risk = entry.riskAnalysis();
                sb.append("Risk: volatility=").append(describe(risk.volatility()))
                        .append("; maxDrawdown=").append(describe(risk.maxDrawdown()))
                        .append("; beta=").append(describe(risk.beta()))
                        .append("; sharpe=").append(describe(risk.sharpe()))
                        .append("; score=").append(describe(risk.riskScore())).append("\n");
            } else {
                sb.append("Risk: unavailable\n");
            }
            if (!entry.dataGaps().isEmpty()) {
                sb.append("Data gaps: ").append(String.join("; ", entry.dataGaps())).append("\n");
            }
        }
        return sb.toString();
    }

    private static void addIfNotNull(Set<BigDecimal> numbers, BigDecimal value) {
        if (value != null) {
            numbers.add(value.stripTrailingZeros());
        }
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String describe(com.finagent.news.sentiment.SentimentResult sentiment) {
        if (sentiment == null) {
            return "not analyzed for this run";
        }
        if (!sentiment.isAvailable()) {
            return "unavailable (" + sentiment.unavailableReason() + ")";
        }
        return sentiment.label() + " (score " + sentiment.score()
                + ", confidence " + sentiment.confidence()
                + ", " + sentiment.positiveCount() + " positive / "
                + sentiment.neutralCount() + " neutral / "
                + sentiment.negativeCount() + " negative of "
                + sentiment.analyzedCount() + " analyzed)";
    }

    private static String describe(com.finagent.analysis.model.VolatilityResult r) {
        return r != null && r.available() ? String.valueOf(r.annualizedVolatility()) : "unavailable";
    }

    private static String describe(com.finagent.analysis.model.MaxDrawdownResult r) {
        return r != null && r.available() ? String.valueOf(r.maxDrawdown()) : "unavailable";
    }

    private static String describe(com.finagent.analysis.model.BetaResult r) {
        return r != null && r.available() ? String.valueOf(r.beta()) : "unavailable";
    }

    private static String describe(com.finagent.analysis.model.SharpeRatioResult r) {
        return r != null && r.available() ? String.valueOf(r.sharpeRatio()) : "unavailable";
    }

    private static String describe(com.finagent.analysis.model.RiskScore r) {
        if (r == null || !r.available()) {
            return "unavailable";
        }
        return r.score() + " (" + r.category() + ")";
    }
}
