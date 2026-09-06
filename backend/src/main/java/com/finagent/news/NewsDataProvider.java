package com.finagent.news;

import com.finagent.news.dto.NewsArticle;

import java.time.LocalDate;
import java.util.List;

/**
 * PORT: swappable financial news source (Hexagonal). Implementations:
 * NewsAPI adapter (production), GNews adapter, deterministic stub (dev/tests).
 * Implementations normalize provider payloads into {@link NewsArticle} and translate all
 * failures into typed exceptions ({@link com.finagent.exception.ProviderException} etc.).
 */
public interface NewsDataProvider {

    /**
     * Searches news for a stock/company query within [from, to] (inclusive), returning at most
     * {@code limit} normalized articles, most recent first.
     */
    List<NewsArticle> searchNews(String query, LocalDate from, LocalDate to, int limit);
}
