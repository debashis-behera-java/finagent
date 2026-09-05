package com.finagent.news.sentiment;

import java.util.List;

/**
 * Structured sentiment outcome for one symbol over a set of news articles.
 *
 * <ul>
 *   <li>{@code score} in {@code [-1.0, +1.0]} (−1 strongly negative, 0 neutral,
 *       +1 strongly positive); {@code null} when {@code label} is UNAVAILABLE.</li>
 *   <li>{@code confidence} in {@code [0.0, 1.0]}: evidence strength, not a
 *       probability — low hit counts yield low confidence by construction.</li>
 *   <li>Counts distinguish analyzed vs skipped articles so consumers can see
 *       exactly what was (and was not) evaluated.</li>
 * </ul>
 */
public record SentimentResult(
        String symbol,
        SentimentLabel label,
        Double score,
        double confidence,
        int articleCount,
        int analyzedCount,
        int unavailableCount,
        int positiveCount,
        int neutralCount,
        int negativeCount,
        UnavailableReason unavailableReason,
        String methodology,
        List<ArticleSentiment> articles) {

    public SentimentResult {
        articles = articles == null ? List.of() : List.copyOf(articles);
    }

    public static SentimentResult unavailable(String symbol, UnavailableReason reason, String methodology) {
        return new SentimentResult(symbol, SentimentLabel.UNAVAILABLE, null, 0.0,
                0, 0, 0, 0, 0, 0, reason, methodology, List.of());
    }

    public boolean isAvailable() {
        return label != SentimentLabel.UNAVAILABLE;
    }
}
