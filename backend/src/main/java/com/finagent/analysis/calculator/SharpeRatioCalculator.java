package com.finagent.analysis.calculator;

import com.finagent.analysis.model.SharpeRatioResult;

import java.util.List;

/**
 * Sharpe ratio (annualized), computed only when sufficient data exists.
 *
 * <p>Formula (documented in docs/risk-methodology.md):</p>
 * <pre>
 *   Sharpe = (mean(r) * tradingDaysPerYear - riskFreeRate) /
 *            (sigma_sample(r) * sqrt(tradingDaysPerYear))
 * </pre>
 *
 * <p>Assumptions: daily periodic returns, risk-free rate expressed as an annual
 * rate (e.g. 0.0425 == 4.25%), annualization factor 252 (configurable). The
 * risk-free rate is a configuration value ({@code finagent.risk.risk-free-rate});
 * when it is not configured the ratio is reported as unavailable - a risk-free
 * rate is never invented.</p>
 *
 * <p>Insufficient observations and zero volatility also yield an unavailable
 * result (division by zero is undefined).</p>
 */
public final class SharpeRatioCalculator {

    private SharpeRatioCalculator() {
    }

    /**
     * @param returns            periodic returns (chronological)
     * @param tradingDaysPerYear annualization factor (daily data assumption: 252)
     * @param riskFreeRate       annual risk-free rate, or null when not configured
     * @return the Sharpe ratio result
     * @throws IllegalArgumentException if {@code returns} is null or the
     *                                  annualization factor is not positive
     */
    public static SharpeRatioResult compute(List<Double> returns, int tradingDaysPerYear,
                                            Double riskFreeRate) {
        if (returns == null) {
            throw new IllegalArgumentException("returns must not be null");
        }
        if (tradingDaysPerYear <= 0) {
            throw new IllegalArgumentException("tradingDaysPerYear must be positive, was " + tradingDaysPerYear);
        }
        if (returns.size() < 2) {
            return SharpeRatioResult.unavailable(
                    "Insufficient observations: need at least 2 returns, got " + returns.size());
        }
        if (riskFreeRate == null) {
            return SharpeRatioResult.unavailable(
                    "Risk-free rate not configured (set finagent.risk.risk-free-rate)");
        }

        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double sumSquares = returns.stream()
                .mapToDouble(r -> {
                    double d = r - mean;
                    return d * d;
                })
                .sum();
        double variance = sumSquares / (returns.size() - 1);
        if (variance == 0.0) {
            return SharpeRatioResult.unavailable(
                    "Sharpe ratio is undefined: zero return variance");
        }
        double annualizedVolatility = Math.sqrt(variance) * Math.sqrt(tradingDaysPerYear);
        double annualizedReturn = mean * tradingDaysPerYear;
        double sharpe = (annualizedReturn - riskFreeRate) / annualizedVolatility;
        return SharpeRatioResult.of(sharpe, annualizedReturn, annualizedVolatility, riskFreeRate);
    }
}