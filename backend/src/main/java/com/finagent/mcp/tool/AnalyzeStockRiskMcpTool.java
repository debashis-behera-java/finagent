package com.finagent.mcp.tool;

import com.finagent.mcp.dto.AnalyzeStockRiskToolResponse;
import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.service.RiskAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;

/**
 * MCP tool {@code analyze_stock_risk}: delegates to the deterministic
 * {@link RiskAnalysisService}. The tool performs NO calculations itself -
 * every metric is produced by the pure analysis engine.
 */
@Slf4j
public class AnalyzeStockRiskMcpTool {

    private final RiskAnalysisService riskAnalysisService;

    public AnalyzeStockRiskMcpTool(RiskAnalysisService riskAnalysisService) {
        this.riskAnalysisService = riskAnalysisService;
    }

    @Tool(name = "analyze_stock_risk",
            description = "Deterministic risk analysis for a stock: annualized volatility, maximum drawdown, "
                    + "beta vs an optional benchmark, Sharpe ratio (when a risk-free rate is configured), and a "
                    + "transparent composite risk score (LOW / MODERATE / HIGH). All metrics are computed by the "
                    + "deterministic analytics engine - never by an LLM. Unavailable metrics are reported as such.")
    public AnalyzeStockRiskToolResponse analyzeStockRisk(
            @ToolParam(description = "Stock ticker symbol, e.g. AAPL or MSFT") String symbol,
            @ToolParam(description = "Benchmark symbol, e.g. SPY (optional; uses configured default)", required = false) String benchmark,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd (optional, default = last 90 days)", required = false) String from,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd (optional, default = today)", required = false) String to) {
        log.debug("MCP analyze_stock_risk invoked for symbol={} benchmark={} from={} to={}", symbol, benchmark, from, to);
        LocalDate fromDate = DateParsers.parseIsoDate(from, "from");
        LocalDate toDate = DateParsers.parseIsoDate(to, "to");
        RiskAnalysisResult result = riskAnalysisService.analyzeStockRisk(symbol, fromDate, toDate, benchmark);
        log.debug("MCP analyze_stock_risk resolved symbol={} score={}", result.symbol(),
                result.riskScore().available() ? result.riskScore().score() : "unavailable");
        return new AnalyzeStockRiskToolResponse(result.symbol(), fromDate, toDate, result);
    }
}