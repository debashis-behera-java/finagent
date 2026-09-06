package com.finagent.news.dto;

import java.time.Instant;

/** Normalized news article - identical shape regardless of the underlying provider. */
public record NewsArticle(
        String query,
        String title,
        String url,
        String source,
        String description,
        String imageUrl,
        Instant publishedAt
) {
}
