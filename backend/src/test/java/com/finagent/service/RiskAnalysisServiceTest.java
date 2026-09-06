package com.finagent.service;

import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.analysis.model.RiskCategory;
import com.finagent.market.BenchmarkDataProvider;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.config.FinAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskAnalysisServiceTest {

    @Mock
    private StockDataService stockDataService;
    @Mock
    private BenchmarkDataProvider benchmarkDataProvider;

    private RiskAnalysisService service;

    @BeforeEach
    void setUp() {
        FinAgentProperties properties = new FinAgentProperties();
        properties.getRisk().setRiskFreeRate(0.0425);
        properties.getRisk().setDefaultBenchmark("SPY");
        service = new RiskAnalysisService(stockDataService, benchmarkDataProvider, properties);
    }

    @Test
    void analyzesCompleteRiskProfile() {
        when(stockDataService.getHistory(eq("AAPL"), any(), any()))
                .thenReturn(new HistoricalSeries("AAPL", bars(100, 110, 105, 120, 115)));
        when(benchmarkDataProvider.getBenchmarkHistory(eq("SPY"), any(), any()))
                .thenReturn(new HistoricalSeries("SPY", bars(400, 405, 403, 410, 408)));

        RiskAnalysisResult result = service.analyzeStockRisk("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null);

        assertThat(result.symbol()).isEqualTo("AAPL");
        assertThat(result.barCount()).isEqualTo(5);
        assertThat(result.volatility().available()).isTrue();
        assertThat(result.volatility().annualizedVolatility()).isGreaterThan(0);
        assertThat(result.maxDrawdown().available()).isTrue();
        assertThat(result.maxDrawdown().maxDrawdown()).isGreaterThan(0);
        // benchmark present and aligned => beta available
        assertThat(result.beta().available()).isTrue();
        // risk-free rate configured => sharpe available
        assertThat(result.sharpe().available()).isTrue();
        assertThat(result.riskScore().available()).isTrue();
        assertThat(result.riskScore().category()).isNotNull();
    }

    @Test
    void sharpeUnavailableWhenRiskFreeRateMissing() {
        FinAgentProperties properties = new FinAgentProperties();
        properties.getRisk().setRiskFreeRate(null);
        properties.getRisk().setDefaultBenchmark("SPY");
        service = new RiskAnalysisService(stockDataService, benchmarkDataProvider, properties);

        when(stockDataService.getHistory(eq("AAPL"), any(), any()))
                .thenReturn(new HistoricalSeries("AAPL", bars(100, 110, 105, 120, 115)));
        when(benchmarkDataProvider.getBenchmarkHistory(eq("SPY"), any(), any()))
                .thenReturn(new HistoricalSeries("SPY", bars(400, 405, 403, 410, 408)));

        RiskAnalysisResult result = service.analyzeStockRisk("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null);

        assertThat(result.sharpe().available()).isFalse();
        assertThat(result.sharpe().reason()).containsIgnoringCase("risk-free");
    }

    @Test
    void betaUnavailableWhenBenchmarkFails() {
        when(stockDataService.getHistory(eq("AAPL"), any(), any()))
                .thenReturn(new HistoricalSeries("AAPL", bars(100, 110, 105, 120, 115)));
        when(benchmarkDataProvider.getBenchmarkHistory(eq("SPY"), any(), any()))
                .thenThrow(new com.finagent.exception.ProviderException("stub failure"));

        RiskAnalysisResult result = service.analyzeStockRisk("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), null);

        assertThat(result.beta().available()).isFalse();
        assertThat(result.beta().reason()).containsIgnoringCase("unavailable");
    }

    @Test
    void invertedDateRangeThrows() {
        assertThatThrownBy(() -> service.analyzeStockRisk("AAPL",
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    void explicitBenchmarkOverridesDefault() {
        when(stockDataService.getHistory(eq("AAPL"), any(), any()))
                .thenReturn(new HistoricalSeries("AAPL", bars(100, 110, 105, 120, 115)));
        when(benchmarkDataProvider.getBenchmarkHistory(eq("MSFT"), any(), any()))
                .thenReturn(new HistoricalSeries("MSFT", bars(400, 405, 403, 410, 408)));

        RiskAnalysisResult result = service.analyzeStockRisk("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), "MSFT");

        assertThat(result.beta().available()).isTrue();
        assertThat(result.beta().benchmarkSymbol()).isEqualTo("MSFT");
        verify(benchmarkDataProvider).getBenchmarkHistory(eq("MSFT"), any(), any());
    }

    private static List<HistoricalBar> bars(double... prices) {
        LocalDate date = LocalDate.of(2026, 8, 1);
        HistoricalBar[] arr = new HistoricalBar[prices.length];
        for (int i = 0; i < prices.length; i++) {
            arr[i] = new HistoricalBar(date.plusDays(i),
                    BigDecimal.valueOf(prices[i]).setScale(2), new BigDecimal("999"),
                    new BigDecimal("1"), BigDecimal.valueOf(prices[i]).setScale(2), 100_000L);
        }
        return List.of(arr);
    }
}