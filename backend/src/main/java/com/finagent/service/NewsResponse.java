package com.finagent.service;

import com.finagent.news.dto.NewsArticle;

import java.util.List;

/** Response body of GET /api/v1/stocks/{symbol}/news. */
public record NewsResponse(
        String symbol,
        int count,
        List<NewsArticle> articles
) {
}
