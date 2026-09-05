package com.finagent.service;

import com.finagent.common.TickerNormalizer;
import com.finagent.news.dto.NewsArticle;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentAnalyzer;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Use-case layer for news sentiment (Phase 9): delegates content scoring to the
 * {@link SentimentAnalyzer} port (deterministic lexicon baseline today, AI later).
 * Never fabricates article text and never infers sentiment from price movement.
 */
@Service
@Slf4j
public class SentimentAnalysisService {

    private final NewsDataService newsDataService;
    private final SentimentAnalyzer sentimentAnalyzer;

    public SentimentAnalysisService(NewsDataService newsDataService, SentimentAnalyzer sentimentAnalyzer) {
        this.newsDataService = newsDataService;
        this.sentimentAnalyzer = sentimentAnalyzer;
    }

    /** Scores already-retrieved articles (used by the research agent — no refetch). */
    public SentimentResult analyzeNews(String symbol, List<NewsArticle> articles) {
        String normalized = TickerNormalizer.normalize(symbol);
        try {
            return sentimentAnalyzer.analyze(normalized, articles);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Sentiment analysis failed for {}: {}", normalized, ex.getMessage());
            return SentimentResult.unavailable(normalized, UnavailableReason.PROVIDER_ERROR,
                    LexiconSentimentAnalyzer.METHODOLOGY);
        }
    }

    /**
     * Fetches recent news through the existing service, then scores it (used by the
     * MCP sentiment tool). Provider failures become UNAVAILABLE/PROVIDER_ERROR —
     * never NEUTRAL.
     */
    public SentimentResult analyzeSymbol(String symbol, LocalDate from, LocalDate to, Integer limit) {
        String normalized = TickerNormalizer.normalize(symbol);
        final List<NewsArticle> articles;
        try {
            articles = newsDataService.getRecentNews(normalized, from, to, limit).articles();
        } catch (RuntimeException ex) {
            log.warn("News fetch for sentiment failed for {}: {}", normalized, ex.getMessage());
            return SentimentResult.unavailable(normalized, UnavailableReason.PROVIDER_ERROR,
                    LexiconSentimentAnalyzer.METHODOLOGY);
        }
        return analyzeNews(normalized, articles);
    }
}
