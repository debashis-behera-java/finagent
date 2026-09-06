package com.finagent.mcp.tool;

import com.finagent.exception.ProviderException;
import com.finagent.news.dto.NewsArticle;
import com.finagent.mcp.dto.FinancialNewsToolResponse;
import com.finagent.service.NewsDataService;
import com.finagent.service.NewsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinancialNewsMcpToolTest {

    @Mock
    private NewsDataService newsDataService;

    private FinancialNewsMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new FinancialNewsMcpTool(newsDataService);
    }

    @Test
    void delegatesAndWrapsArticles() {
        NewsArticle article = new NewsArticle("AAPL", "Apple beats expectations", "https://example.com/1",
                "Reuters", "Above consensus.", "https://example.com/1.jpg", Instant.parse("2026-08-27T16:30:00Z"));
        when(newsDataService.getRecentNews("AAPL", null, null, 5))
                .thenReturn(new NewsResponse("AAPL", 1, List.of(article)));

        FinancialNewsToolResponse response = tool.searchFinancialNews("AAPL", null, null, 5);

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.articles()).hasSize(1);
        assertThat(response.articles().get(0).title()).isEqualTo("Apple beats expectations");
        verify(newsDataService).getRecentNews(eq("AAPL"), any(), any(), eq(5));
    }

    @Test
    void emptyResultReturnsZeroCount() {
        when(newsDataService.getRecentNews("AAPL", null, null, null))
                .thenReturn(new NewsResponse("AAPL", 0, List.of()));

        FinancialNewsToolResponse response = tool.searchFinancialNews("AAPL", null, null, null);

        assertThat(response.count()).isZero();
        assertThat(response.articles()).isEmpty();
        verify(newsDataService).getRecentNews(eq("AAPL"), any(), any(), any());
    }

    @Test
    void parsesOptionalIsoDates() {
        when(newsDataService.getRecentNews("AAPL",
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 14), 20))
                .thenReturn(new NewsResponse("AAPL", 0, List.of()));

        tool.searchFinancialNews("AAPL", "2026-08-01", "2026-08-14", 20);

        verify(newsDataService).getRecentNews(eq("AAPL"),
                eq(java.time.LocalDate.of(2026, 8, 1)), eq(java.time.LocalDate.of(2026, 8, 14)), eq(20));
    }

    @Test
    void malformedDateIsRejected() {
        assertThatThrownBy(() -> tool.searchFinancialNews("AAPL", "2026-13-01", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void invalidSymbolIsRejectedByServiceLayer() {
        when(newsDataService.getRecentNews(anyString(), any(), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Invalid symbol 'WAY_TOO_LONG_SYMBOL'"));

        assertThatThrownBy(() -> tool.searchFinancialNews("WAY_TOO_LONG_SYMBOL", null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid symbol");
    }

    @Test
    void providerFailurePropagates() {
        when(newsDataService.getRecentNews(anyString(), any(), any(), anyInt()))
                .thenThrow(new ProviderException("News provider rate limit reached"));

        assertThatThrownBy(() -> tool.searchFinancialNews("AAPL", null, null, 10))
                .isInstanceOf(ProviderException.class);
    }
}