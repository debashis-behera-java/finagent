package com.finagent.service;

import com.finagent.config.FinAgentProperties;
import com.finagent.exception.ProviderException;
import com.finagent.news.dto.NewsArticle;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentAnalyzer;
import com.finagent.news.sentiment.SentimentLabel;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SentimentAnalysisServiceTest {

    @Mock
    private NewsDataService newsDataService;

    private SentimentAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new SentimentAnalysisService(newsDataService,
                new LexiconSentimentAnalyzer(new FinAgentProperties()));
    }

    private static NewsArticle article(String title) {
        return new NewsArticle("AAPL", title, "https://example.com/x", "Wire",
                "Update.", null, Instant.parse("2026-08-27T16:30:00Z"));
    }

    @Test
    void analyzesProvidedArticlesWithoutRefetch() {
        SentimentResult result = service.analyzeNews("aapl",
                List.of(article("Record profits beat expectations")));

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.label()).isEqualTo(SentimentLabel.POSITIVE);
        assertThat(result.isAvailable()).isTrue();
    }

    @Test
    void emptyArticlesMapsToNoArticles() {
        SentimentResult result = service.analyzeNews("AAPL", List.of());

        assertThat(result.label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.NO_ARTICLES);
    }

    @Test
    void analyzerFailureMapsToProviderError() {
        SentimentAnalyzer failing = (symbol, articles) -> {
            throw new RuntimeException("lexicon blew up");
        };
        SentimentAnalysisService failingService = new SentimentAnalysisService(newsDataService, failing);

        SentimentResult result = failingService.analyzeNews("AAPL",
                List.of(article("Record profits")));

        assertThat(result.label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.PROVIDER_ERROR);
    }

    @Test
    void analyzeSymbolFetchesThenScores() {
        when(newsDataService.getRecentNews(eq("AAPL"), any(LocalDate.class), any(LocalDate.class), anyInt()))
                .thenReturn(new NewsResponse("AAPL", 1,
                        List.of(article("Downgrade amid losses"))));

        SentimentResult result = service.analyzeSymbol("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 10);

        assertThat(result.label()).isEqualTo(SentimentLabel.NEGATIVE);
        assertThat(result.articleCount()).isOne();
        verify(newsDataService).getRecentNews(eq("AAPL"), any(LocalDate.class), any(LocalDate.class), eq(10));
    }

    @Test
    void analyzeSymbolProviderFailureIsUnavailableNotNeutral() {
        when(newsDataService.getRecentNews(eq("FAIL"), any(), any(), any()))
                .thenThrow(new ProviderException("Stub news provider simulated failure"));

        SentimentResult result = service.analyzeSymbol("FAIL", null, null, null);

        assertThat(result.label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(UnavailableReason.PROVIDER_ERROR);
        assertThat(result.label()).isNotEqualTo(SentimentLabel.NEUTRAL);
    }
}
