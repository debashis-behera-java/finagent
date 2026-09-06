package com.finagent.mcp.dto;

import com.finagent.analysis.model.RiskAnalysisResult;

/**
 * MCP tool response DTO for {@code analyze_stock_risk}. Wraps the deterministic
 * {@link RiskAnalysisResult} so the MCP client never sees provider-specific shapes.
 */
public record AnalyzeStockRiskToolResponse(
        String symbol,
        java.time.LocalDate from,
        java.time.LocalDate to,
        RiskAnalysisResult result
) {
}