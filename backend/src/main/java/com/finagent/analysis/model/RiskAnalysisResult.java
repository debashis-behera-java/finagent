package com.finagent.analysis.model;

import java.time.LocalDate;

/**
 * Top-level deterministic risk analysis for one stock. Sub-results carry their
 * own {@code available} flag + {@code reason} when a metric could not be
 * computed from real data - nothing is ever fabricated.
 */
public record RiskAnalysisResult(
        String symbol,
        LocalDate from,
        LocalDate to,
        int barCount,
        VolatilityResult volatility,
        MaxDrawdownResult maxDrawdown,
        BetaResult beta,
        SharpeRatioResult sharpe,
        RiskScore riskScore) {
}