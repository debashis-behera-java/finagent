package com.finagent.news.sentiment;

/**
 * Article- or aggregate-level sentiment label.
 *
 * <p>{@code UNAVAILABLE} is a first-class state — it means sentiment could not be
 * determined (no articles, provider failure, no usable text), which is different
 * from {@code NEUTRAL} (articles analyzed, no directional signal).</p>
 */
public enum SentimentLabel {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
    UNAVAILABLE
}
