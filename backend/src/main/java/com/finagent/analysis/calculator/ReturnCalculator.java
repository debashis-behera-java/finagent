package com.finagent.analysis.calculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Periodic (simple) return calculation.
 *
 * <p>Formula (documented in docs/risk-methodology.md):</p>
 * <pre>
 *   r_t = (P_t / P_{t-1}) - 1    for t = 1 .. n-1
 * </pre>
 *
 * <p>Returns are deterministic doubles derived from the supplied closing prices.
 * Null or non-positive prices are invalid input ({@link IllegalArgumentException}).
 * A list with fewer than two prices yields an empty return series (insufficient
 * data - the caller decides how to report it).</p>
 */
public final class ReturnCalculator {

    private ReturnCalculator() {
    }

    /**
     * Computes simple periodic returns from chronological closing prices.
     *
     * @param prices chronological closing prices (non-null, all strictly positive)
     * @return returns of size {@code n-1}; empty when fewer than two prices
     * @throws IllegalArgumentException if {@code prices} is null or contains
     *                                  null / non-positive entries
     */
    public static List<Double> compute(List<BigDecimal> prices) {
        if (prices == null) {
            throw new IllegalArgumentException("prices must not be null");
        }
        List<Double> returns = new ArrayList<>(Math.max(0, prices.size() - 1));
        for (int i = 0; i < prices.size(); i++) {
            BigDecimal price = prices.get(i);
            if (price == null) {
                throw new IllegalArgumentException("price at index " + i + " is null");
            }
            if (price.signum() <= 0) {
                throw new IllegalArgumentException("price at index " + i + " must be positive, was " + price);
            }
            if (i > 0) {
                BigDecimal previous = prices.get(i - 1);
                returns.add(price.divide(previous, java.math.MathContext.DECIMAL64).doubleValue() - 1.0);
            }
        }
        return returns;
    }
}