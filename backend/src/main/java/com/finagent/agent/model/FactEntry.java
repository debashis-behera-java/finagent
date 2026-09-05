package com.finagent.agent.model;

import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.Quote;
import com.finagent.news.sentiment.SentimentResult;

import java.time.LocalDate;
import java.util.List;

/**
 * Validated facts for one ticker. Any data source that failed or returned unusable data
 * is represented as {@code null} (or zero counts) with a human-readable entry in
 * {@code dataGaps} — gaps are flagged, never silently filled in.
 *
 * <p>{@code newsSentiment} is present only when the run asked for sentiment analysis
 * (see the agent's sentiment policy); {@code null} means "not analyzed", which is
 * different from an UNAVAILABLE sentiment result ("analyzed, but no usable signal").</p>
 */
public record FactEntry(
        String symbol,
        Quote quote,
        Fundamentals fundamentals,
        int historyBarCount,
        LocalDate historyFrom,
        LocalDate historyTo,
        int newsCount,
        List<String> newsHeadlines,
        RiskAnalysisResult riskAnalysis,
        SentimentResult newsSentiment,
        List<String> dataGaps) {

    public FactEntry {
        newsHeadlines = newsHeadlines == null ? List.of() : List.copyOf(newsHeadlines);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    /** True when every core source failed — the entry carries no usable facts. */
    public boolean isEmpty() {
        return quote == null
                && fundamentals == null
                && historyBarCount == 0
                && newsCount == 0
                && riskAnalysis == null;
    }
}
