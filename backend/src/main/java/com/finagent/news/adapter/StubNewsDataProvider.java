package com.finagent.news.adapter;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.news.NewsDataProvider;
import com.finagent.news.dto.NewsArticle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory news provider for offline development and tests.
 * Same query always produces the same articles (seeded from the query hash).
 * Special queries: "UNKNOWN" -> UnknownSymbolException, "FAIL" -> ProviderException.
 */
public class StubNewsDataProvider implements NewsDataProvider {

    private static final String[] TITLE_TEMPLATES = {
            "%s beats earnings expectations",
            "Analysts raise %s price targets",
            "%s announces new product line",
            "Investors weigh risks around %s",
            "%s supply chain update calms markets",
            "%s dividend policy under spotlight"
    };
    private static final String[] SOURCES = {"Stub Finance", "Market Wire", "Global Business Daily"};

    @Override
    public List<NewsArticle> searchNews(String query, LocalDate from, LocalDate to, int limit) {
        failForSpecialQueries(query);
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ZoneOffset.UTC);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(7);
        long spanDays = Math.max(1, ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1);

        int hash = Math.abs(query.hashCode());
        List<NewsArticle> articles = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            LocalDate date = effectiveFrom.plusDays((hash + i * 2L) % spanDays);
            Instant publishedAt = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                    .plus(hash % 24, ChronoUnit.HOURS)
                    .plus((hash + i) % 60, ChronoUnit.MINUTES);
            articles.add(new NewsArticle(
                    query,
                    TITLE_TEMPLATES[(hash + i) % TITLE_TEMPLATES.length].formatted(query),
                    "https://news.stub/" + query.toLowerCase() + "/article-" + i,
                    SOURCES[(hash + i) % SOURCES.length],
                    "Deterministic stub article #" + i + " about " + query
                            + " (FINAGENT offline mode).",
                    "https://images.stub/" + query.toLowerCase() + "/image-" + i,
                    publishedAt));
        }
        return articles;
    }

    private void failForSpecialQueries(String query) {
        if ("UNKNOWN".equalsIgnoreCase(query)) {
            throw new UnknownSymbolException(query);
        }
        if ("FAIL".equalsIgnoreCase(query)) {
            throw new ProviderException("Stub news provider simulated failure for query " + query);
        }
    }
}
