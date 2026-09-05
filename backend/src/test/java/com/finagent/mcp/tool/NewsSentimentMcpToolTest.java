package com.finagent.mcp.tool;

import com.finagent.exception.ProviderException;
import com.finagent.mcp.dto.NewsSentimentToolResponse;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentLabel;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import com.finagent.service.SentimentAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsSentimentMcpToolTest {

    @Mock
    private SentimentAnalysisService sentimentAnalysisService;

    private NewsSentimentMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new NewsSentimentMcpTool(sentimentAnalysisService);
    }

    private static SentimentResult positive() {
        return new SentimentResult("AAPL", SentimentLabel.POSITIVE, 0.6, 0.8,
                3, 3, 0, 2, 1, 0, null,
                LexiconSentimentAnalyzer.METHODOLOGY, List.of());
    }

    @Test
    void delegatesToSentimentService() {
        when(sentimentAnalysisService.analyzeSymbol(eq("AAPL"),
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 31)), eq(10)))
                .thenReturn(positive());

        NewsSentimentToolResponse response = tool.analyzeNewsSentiment(
                "AAPL", "2026-08-01", "2026-08-31", 10);

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.articleCount()).isEqualTo(3);
        assertThat(response.sentiment().label()).isEqualTo(SentimentLabel.POSITIVE);
        assertThat(response.sentiment().score()).isEqualTo(0.6);
        verify(sentimentAnalysisService).analyzeSymbol(eq("AAPL"),
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 31)), eq(10));
    }

    @Test
    void defaultsArePassedThroughAsNull() {
        when(sentimentAnalysisService.analyzeSymbol(eq("AAPL"), any(), any(), any()))
                .thenReturn(positive());

        NewsSentimentToolResponse response = tool.analyzeNewsSentiment("AAPL", null, null, null);

        assertThat(response.from()).isNull();
        assertThat(response.to()).isNull();
        verify(sentimentAnalysisService).analyzeSymbol(eq("AAPL"), eq(null), eq(null), eq(null));
    }

    @Test
    void malformedDateRejectedBeforeServiceCall() {
        assertThatThrownBy(() -> tool.analyzeNewsSentiment("AAPL", "not-a-date", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from'");
        verifyNoInteractions(sentimentAnalysisService);
    }

    @Test
    void providerFailureSurfacesAsUnavailable() {
        when(sentimentAnalysisService.analyzeSymbol(eq("FAIL"), any(), any(), any()))
                .thenReturn(SentimentResult.unavailable("FAIL", UnavailableReason.PROVIDER_ERROR,
                        LexiconSentimentAnalyzer.METHODOLOGY));

        NewsSentimentToolResponse response = tool.analyzeNewsSentiment("FAIL", null, null, null);

        assertThat(response.sentiment().label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(response.sentiment().unavailableReason()).isEqualTo(UnavailableReason.PROVIDER_ERROR);
    }

    @Test
    void serviceExceptionPropagates() {
        when(sentimentAnalysisService.analyzeSymbol(eq("FAIL"), any(), any(), any()))
                .thenThrow(new ProviderException("boom"));

        assertThatThrownBy(() -> tool.analyzeNewsSentiment("FAIL", null, null, null))
                .isInstanceOf(ProviderException.class);
    }
}
