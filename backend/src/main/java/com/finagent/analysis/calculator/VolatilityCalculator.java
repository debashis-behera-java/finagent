package com.finagent.analysis.calculator;

import java.util.List;

/**
 * Annualized volatility from periodic returns.
 *
 * <p>Formula (documented in docs/risk-methodology.md):</p>
 * <pre>
 *   sigma_annual = sigma_sample(r) * sqrt(tradingDaysPerYear)
 * </pre>
 * where {@code sigma_sample} is the sample standard deviation (n-1 denominator).
 *
 * <p>Assumption: data is daily; the annualization factor defaults to 252 trading
 * days per year (configurable via {@code finagent.risk.trading-days-per-year}).
 * The factor can be overridden per call without changing the calculator.</p>
 *
 * <p>Zero variance is a valid, deterministic result (annualized volatility of 0),
 * NOT an error. Fewer than two returns is insufficient data.</p>
 */
public final class VolatilityCalculator {

    private VolatilityCalculator() {
    }

    /**
     * @param returns            periodic returns (chronological)
     * @param tradingDaysPerYear annualization factor (e.g. 252 for daily data)
     * @return the annualized volatility result
     * @throws IllegalArgumentException if {@code returns} is null or the
     *                                  annualization factor is not positive
     */
    public static com.finagent.analysis.model.VolatilityResult annualized(List<Double> returns,
                                                                          int tradingDaysPerYear) {
        if (returns == null) {
            throw new IllegalArgumentException("returns must not be null");
        }
        if (tradingDaysPerYear <= 0) {
            throw new IllegalArgumentException("tradingDaysPerYear must be positive, was " + tradingDaysPerYear);
        }
        if (returns.size() < 2) {
            return com.finagent.analysis.model.VolatilityResult.unavailable(
                    "Insufficient observations: need at least 2 returns, got " + returns.size());
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSquares = returns.stream()
                .mapToDouble(r -> {
                    double d = r - mean;
                    return d * d;
                })
                .sum();
        double variance = sumSquares / (returns.size() - 1);
        double annualized = Math.sqrt(variance) * Math.sqrt(tradingDaysPerYear);
        return com.finagent.analysis.model.VolatilityResult.of(annualized);
    }
}