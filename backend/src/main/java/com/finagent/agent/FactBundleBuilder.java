package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import com.finagent.news.dto.NewsArticle;
import com.finagent.news.sentiment.SentimentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles validated raw tool outputs into the immutable {@link FactBundle} (Phase 7).
 *
 * <p>Validation policy (null/NaN/stale checks): a missing or unusable source never fails
 * the entry — it becomes {@code null} with a human-readable data gap. The LLM and the
 * PDF report must treat "unavailable" as a first-class answer, never as a license to
 * fill in numbers.</p>
 */
@Component
@Slf4j
public class FactBundleBuilder {

    public FactEntry buildEntry(String symbol,
                                Quote quote,
                                Fundamentals fundamentals,
                                HistoricalSeries history,
                                List<NewsArticle> articles,
                                RiskAnalysisResult riskAnalysis) {
        return buildEntry(symbol, quote, fundamentals, history, articles, riskAnalysis, null);
    }

    /**
     * Full entry assembly including an optional pre-computed {@link SentimentResult}.
     * A {@code null} sentiment means "not analyzed for this run" — the entry records
     * no gap for it. An UNAVAILABLE sentiment (analyzed, no usable signal) is kept
     * as a grounded fact with its reason.
     */
    public FactEntry buildEntry(String symbol,
                                Quote quote,
                                Fundamentals fundamentals,
                                HistoricalSeries history,
                                List<NewsArticle> articles,
                                RiskAnalysisResult riskAnalysis,
                                SentimentResult newsSentiment) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Fact entry symbol must not be blank");
        }
        List<String> gaps = new ArrayList<>();

        Quote validQuote = validQuote(symbol, quote, gaps);
        Fundamentals validFundamentals = fundamentals != null ? fundamentals : null;
        if (fundamentals == null) {
            gaps.add("fundamentals unavailable for " + symbol);
        }
        int barCount = 0;
        java.time.LocalDate historyFrom = null;
        java.time.LocalDate historyTo = null;
        if (history == null || history.bars() == null || history.bars().isEmpty()) {
            gaps.add("price history unavailable for " + symbol);
        } else {
            barCount = history.bars().size();
            historyFrom = history.bars().get(0).date();
            historyTo = history.bars().get(barCount - 1).date();
        }

        List<NewsArticle> safeArticles = articles == null ? List.of() : articles;
        List<String> headlines = new ArrayList<>();
        for (NewsArticle article : safeArticles) {
            if (article != null && article.title() != null && !article.title().isBlank()) {
                headlines.add(article.title());
            }
        }
        if (safeArticles.isEmpty()) {
            gaps.add("no news articles found for " + symbol);
        }

        if (riskAnalysis == null) {
            gaps.add("risk analysis unavailable for " + symbol);
        }

        FactEntry entry = new FactEntry(symbol, validQuote, validFundamentals, barCount,
                historyFrom, historyTo, safeArticles.size(), headlines, riskAnalysis, newsSentiment, gaps);
        log.debug("Built fact entry for {}: {} gap(s)", symbol, gaps.size());
        return entry;
    }

    public FactBundle buildBundle(String requestText, List<FactEntry> entries) {
        if (requestText == null || requestText.isBlank()) {
            throw new IllegalArgumentException("Request text must not be blank");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Fact bundle requires at least one entry");
        }
        List<String> tickers = entries.stream().map(FactEntry::symbol).toList();
        return new FactBundle(requestText, tickers, Instant.now(), entries);
    }

    private Quote validQuote(String symbol, Quote quote, List<String> gaps) {
        if (quote == null) {
            gaps.add("quote unavailable for " + symbol);
            return null;
        }
        BigDecimal price = quote.price();
        if (price == null || price.signum() <= 0
                || Double.isNaN(price.doubleValue()) || Double.isInfinite(price.doubleValue())) {
            gaps.add("quote price invalid for " + symbol);
            return null;
        }
        return quote;
    }
}
