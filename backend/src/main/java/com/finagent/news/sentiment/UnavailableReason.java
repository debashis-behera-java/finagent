package com.finagent.news.sentiment;

/**
 * Why a {@link SentimentResult} is {@link SentimentLabel#UNAVAILABLE}.
 * Kept distinct from {@link SentimentLabel#NEUTRAL} on purpose: "no data" and
 * "balanced data" must never be confused downstream.
 */
public enum UnavailableReason {
    /** News retrieval returned zero articles. */
    NO_ARTICLES,
    /** The news provider call itself failed. */
    PROVIDER_ERROR,
    /** Articles existed but none contained usable title/description text. */
    INSUFFICIENT_CONTENT
}
