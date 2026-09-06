package com.finagent.news.adapter;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.news.dto.NewsArticle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubNewsDataProviderTest {

    private final StubNewsDataProvider provider = new StubNewsDataProvider();

    @Test
    void returnsRequestedNumberOfDeterministicArticles() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 14);

        List<NewsArticle> first = provider.searchNews("AAPL", from, to, 5);
        List<NewsArticle> second = provider.searchNews("AAPL", from, to, 5);

        assertThat(first).hasSize(5);
        assertThat(first).isEqualTo(second); // deterministic
    }

    @Test
    void articlesAreWellFormedAndWithinRange() {
        LocalDate from = LocalDate.of(2026, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 14);

        List<NewsArticle> articles = provider.searchNews("MSFT", from, to, 4);

        assertThat(articles).allSatisfy(article -> {
            assertThat(article.query()).isEqualTo("MSFT");
            assertThat(article.title()).contains("MSFT");
            assertThat(article.url()).isNotBlank();
            assertThat(article.source()).isNotBlank();
            assertThat(article.description()).isNotBlank();
            assertThat(article.imageUrl()).isNotBlank();
            assertThat(article.publishedAt()).isNotNull();
        });
    }

    @Test
    void defaultsRangeToLastSevenDaysWhenDatesMissing() {
        List<NewsArticle> articles = provider.searchNews("TSLA", null, null, 3);

        assertThat(articles).hasSize(3);
        LocalDate today = LocalDate.now();
        assertThat(articles).allSatisfy(a ->
                assertThat(a.publishedAt()).isAfter(today.minusDays(8).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    }

    @Test
    void unknownQueryThrows() {
        assertThatThrownBy(() -> provider.searchNews("UNKNOWN", null, null, 5))
                .isInstanceOf(UnknownSymbolException.class);
    }

    @Test
    void failQueryThrowsProviderException() {
        assertThatThrownBy(() -> provider.searchNews("FAIL", null, null, 5))
                .isInstanceOf(ProviderException.class)
                .isNotInstanceOf(UnknownSymbolException.class);
    }
}
