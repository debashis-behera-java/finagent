package com.finagent.mcp.dto;

import com.finagent.news.dto.NewsArticle;

import java.time.LocalDate;
import java.util.List;

/**
 * MCP tool response DTO for {@code search_financial_news}. Reuses the shared
 * {@link NewsArticle} domain DTO. No sentiment fields in scope (Phase 5 boundary).
 */
public record FinancialNewsToolResponse(
        String symbol,
        LocalDate from,
        LocalDate to,
        int count,
        List<NewsArticle> articles
) {

    public FinancialNewsToolResponse {
        articles = List.copyOf(articles);
    }
}