package com.finagent.service;

import com.finagent.common.TickerNormalizer;
import com.finagent.news.NewsDataProvider;
import com.finagent.news.dto.NewsArticle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Use-case layer for financial news retrieval: normalizes/validates the symbol,
 * applies default date range (last 7 days) and result limit, delegates to the
 * active {@link NewsDataProvider}.
 */
@Service
@Slf4j
public class NewsDataService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    static final int DEFAULT_RANGE_DAYS = 7;

    private final NewsDataProvider newsDataProvider;

    public NewsDataService(NewsDataProvider newsDataProvider) {
        this.newsDataProvider = newsDataProvider;
    }

    /**
     * Recent headlines for a stock/company. `from`/`to` optional (default: last 7 days);
     * `limit` optional (default 20, max 50).
     */
    public NewsResponse getRecentNews(String symbol, LocalDate from, LocalDate to, Integer limit) {
        String query = TickerNormalizer.normalize(symbol);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(DEFAULT_RANGE_DAYS);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' date must not be after 'to' date");
        }
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        log.debug("Fetching news for {} [{} .. {}] limit={}", query, effectiveFrom, effectiveTo, effectiveLimit);
        List<NewsArticle> articles = newsDataProvider.searchNews(query, effectiveFrom, effectiveTo, effectiveLimit);
        return new NewsResponse(query, articles.size(), articles);
    }
}
