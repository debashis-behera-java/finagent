package com.finagent.mcp.tool;

import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.analysis.model.RiskCategory;
import com.finagent.mcp.dto.AnalyzeStockRiskToolResponse;
import com.finagent.service.RiskAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeStockRiskMcpToolTest {

    @Mock
    private RiskAnalysisService riskAnalysisService;

    private AnalyzeStockRiskMcpTool tool;

    @BeforeEach
    void setUp() {
        tool = new AnalyzeStockRiskMcpTool(riskAnalysisService);
    }

    @Test
    void delegatesToRiskServiceAndWrapsResult() {
        RiskAnalysisResult result = new RiskAnalysisResult("AAPL",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), 5,
                null, null, null, null,
                new com.finagent.analysis.model.RiskScore(true, 42.5, RiskCategory.MODERATE,
                        java.util.Map.of(), java.util.List.of(), null));
        when(riskAnalysisService.analyzeStockRisk(eq("AAPL"), any(), any(), any())).thenReturn(result);

        AnalyzeStockRiskToolResponse response = tool.analyzeStockRisk("AAPL", "SPY",
                "2026-08-01", "2026-08-07");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.result()).isSameAs(result);
        verify(riskAnalysisService).analyzeStockRisk(eq("AAPL"),
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 7)), eq("SPY"));
    }

    @Test
    void parsesOptionalDatesAndBenchmark() {
        RiskAnalysisResult result = new RiskAnalysisResult("MSFT",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7), 5,
                null, null, null, null,
                new com.finagent.analysis.model.RiskScore(true, 10.0, RiskCategory.LOW,
                        java.util.Map.of(), java.util.List.of(), null));
        when(riskAnalysisService.analyzeStockRisk(eq("MSFT"), any(), any(), any())).thenReturn(result);

        AnalyzeStockRiskToolResponse response = tool.analyzeStockRisk("MSFT", null, null, null);

        assertThat(response.symbol()).isEqualTo("MSFT");
        verify(riskAnalysisService).analyzeStockRisk(eq("MSFT"), any(), any(), any());
    }

    @Test
    void malformedDateIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> tool.analyzeStockRisk("AAPL", null, "not-a-date", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }
}