package com.finagent.news.sentiment;

import com.finagent.news.dto.NewsArticle;

import java.util.List;

/**
 * Port for news sentiment analysis. The initial implementation is the deterministic
 * lexicon analyzer (offline, no LLM); a future {@code AiSentimentAnalyzer} can
 * implement this same interface without touching callers.
 */
public interface SentimentAnalyzer {

    /**
     * Analyzes article title/description text that is actually present.
     * Never fabricates text and never infers sentiment from price movement.
     * Missing/empty input yields an UNAVAILABLE result with a reason — never an
     * exception for empty input.
     */
    SentimentResult analyze(String symbol, List<NewsArticle> articles);
}
