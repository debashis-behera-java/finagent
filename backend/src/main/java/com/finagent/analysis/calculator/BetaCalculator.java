package com.finagent.analysis.calculator;

import com.finagent.analysis.model.BetaResult;

import java.util.List;

/**
 * Beta calculation relative to a benchmark, based on returns (never raw prices).
 *
 * <p>Formula (documented in docs/risk-methodology.md):</p>
 * <pre>
 *   beta = Cov(r_stock, r_benchmark) / Var(r_benchmark)
 * </pre>
 * using sample covariance/variance (n-1 denominator) over aligned observations.
 *
 * <p>Alignment (by date) happens in the orchestration layer before this
 * calculator is called; the two arrays must therefore have equal length and be
 * pairwise-aligned. Requirements: at least {@code minObservations} pairs, and a
 * strictly positive benchmark variance (zero or undefined benchmark variance
 * makes beta undefined - it is reported as unavailable, never guessed).</p>
 */
public final class BetaCalculator {

    private BetaCalculator() {
    }

    /**
     * @param stockReturns      stock periodic returns, aligned with the benchmark
     * @param benchmarkReturns  benchmark periodic returns, aligned with the stock
     * @param minObservations   minimum number of aligned observations required
     * @param benchmarkSymbol   benchmark symbol (reported in the result, for context)
     * @return the beta result
     * @throws IllegalArgumentException if either array is null, the arrays differ
     *                                  in length, or {@code minObservations} is &lt; 1
     */
    public static BetaResult compute(List<Double> stockReturns, List<Double> benchmarkReturns,
                                     int minObservations, String benchmarkSymbol) {
        if (stockReturns == null || benchmarkReturns == null) {
            throw new IllegalArgumentException("stockReturns and benchmarkReturns must not be null");
        }
        if (stockReturns.size() != benchmarkReturns.size()) {
            throw new IllegalArgumentException("stockReturns and benchmarkReturns must be aligned: "
                    + stockReturns.size() + " vs " + benchmarkReturns.size());
        }
        if (minObservations < 1) {
            throw new IllegalArgumentException("minObservations must be >= 1, was " + minObservations);
        }
        if (stockReturns.size() < minObservations) {
            return BetaResult.unavailable(
                    "Insufficient aligned observations: need at least " + minObservations
                            + ", got " + stockReturns.size(),
                    benchmarkSymbol);
        }

        int n = stockReturns.size();
        double stockMean = stockReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double benchMean = benchmarkReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        double covariance = 0.0;
        double benchmarkVariance = 0.0;
        for (int i = 0; i < n; i++) {
            double dStock = stockReturns.get(i) - stockMean;
            double dBench = benchmarkReturns.get(i) - benchMean;
            covariance += dStock * dBench;
            benchmarkVariance += dBench * dBench;
        }
        covariance /= (n - 1);
        benchmarkVariance /= (n - 1);

        if (benchmarkVariance == 0.0) {
            return BetaResult.unavailable("Beta is undefined: benchmark variance is zero", benchmarkSymbol);
        }
        return BetaResult.of(covariance / benchmarkVariance, benchmarkSymbol);
    }
}