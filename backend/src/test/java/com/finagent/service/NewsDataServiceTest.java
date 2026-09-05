package com.finagent.service;

import com.finagent.exception.ProviderException;
import com.finagent.exception.ResourceNotFoundException;
import com.finagent.news.NewsDataProvider;
import com.finagent.news.dto.NewsArticle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
class NewsDataServiceTest {

    @Mock
    private NewsDataProvider newsDataProvider;

    @InjectMocks
    private NewsDataService service;

    @Test
    void normalizesSymbolAndAppliesDefaults() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of());

        service.getRecentNews("  aapl ", null, null, null);

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(newsDataProvider).searchNews(eq("AAPL"), from.capture(), to.capture(),
                eq(NewsDataService.DEFAULT_LIMIT));
        assertThat(to.getValue()).isEqualTo(LocalDate.now());
        assertThat(from.getValue()).isEqualTo(LocalDate.now().minusDays(NewsDataService.DEFAULT_RANGE_DAYS));
    }

    @Test
    void clampsLimitToConfiguredMaximum() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        service.getRecentNews("AAPL", null, null, 500);

        verify(newsDataProvider).searchNews(eq("AAPL"), any(), any(), eq(NewsDataService.MAX_LIMIT));
    }

    @Test
    void usesExplicitRangeAndLimit() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt())).thenReturn(List.of());

        service.getRecentNews("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14), 5);

        verify(newsDataProvider).searchNews("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 14), 5);
    }

    @Test
    void invalidSymbolIsRejected() {
        assertThatThrownBy(() -> service.getRecentNews("WAY_TOO_LONG!", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invertedDateRangeIsRejected() {
        assertThatThrownBy(() -> service.getRecentNews("AAPL",
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void buildsResponseWithCount() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt())).thenReturn(List.of(
                new NewsArticle("AAPL", "t1", "u1", "s1", "desc1", "img1", null),
                new NewsArticle("AAPL", "t2", "u2", "s2", "desc2", "img2", null)));

        NewsResponse response = service.getRecentNews("AAPL", null, null, null);

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.count()).isEqualTo(2);
        assertThat(response.articles()).hasSize(2);
    }

    @Test
    void providerFailuresPropagate() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt()))
                .thenThrow(new ProviderException("News provider rate limit reached"));

        assertThatThrownBy(() -> service.getRecentNews("AAPL", null, null, null))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void providerUnknownSymbolPropagates() {
        when(newsDataProvider.searchNews(anyString(), any(), any(), anyInt()))
                .thenThrow(new ResourceNotFoundException("Unknown symbol: UNKNOWN"));

        assertThatThrownBy(() -> service.getRecentNews("UNKNOWN", null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
