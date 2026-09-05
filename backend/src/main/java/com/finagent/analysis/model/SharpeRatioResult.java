package com.finagent.analysis.model;

/**
 * Sharpe-ratio result. The ratio and the annualized return/volatility it was
 * derived from are reported. {@code available=false} means the ratio could not
 * be computed (insufficient data, zero volatility, or an unconfigured risk-free
 * rate) - see {@code reason}.
 */
public record SharpeRatioResult(
        boolean available,
        Double sharpeRatio,
        Double annualizedReturn,
        Double annualizedVolatility,
        Double riskFreeRate,
        String reason) {

    public static SharpeRatioResult of(double sharpeRatio, double annualizedReturn,
                                       double annualizedVolatility, double riskFreeRate) {
        return new SharpeRatioResult(true, sharpeRatio, annualizedReturn, annualizedVolatility,
                riskFreeRate, null);
    }

    public static SharpeRatioResult unavailable(String reason) {
        return new SharpeRatioResult(false, null, null, null, null, reason);
    }
}