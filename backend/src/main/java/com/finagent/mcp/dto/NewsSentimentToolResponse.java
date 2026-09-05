package com.finagent.mcp.dto;

import com.finagent.news.sentiment.SentimentResult;

import java.time.LocalDate;

/**
 * MCP tool response DTO for {@code analyze_news_sentiment}. Carries the structured
 * {@link SentimentResult} — label, score, confidence, counts and methodology —
 * never raw article bodies.
 */
public record NewsSentimentToolResponse(
        String symbol,
        LocalDate from,
        LocalDate to,
        int articleCount,
        SentimentResult sentiment
) {
}
