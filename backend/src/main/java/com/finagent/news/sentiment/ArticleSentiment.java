package com.finagent.news.sentiment;

/**
 * Per-article sentiment outcome. {@code score} is in {@code [-1.0, +1.0]} and the
 * hit counts expose exactly which evidence produced it — every result is explainable.
 */
public record ArticleSentiment(
        String articleTitle,
        String articleUrl,
        SentimentLabel label,
        double score,
        int positiveHits,
        int negativeHits) {
}
