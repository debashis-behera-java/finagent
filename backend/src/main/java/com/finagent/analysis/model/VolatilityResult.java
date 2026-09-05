package com.finagent.analysis.model;

/**
 * Annualized volatility result. {@code available=false} means the metric
 * could not be computed from the supplied data (see {@code reason}).
 */
public record VolatilityResult(boolean available, Double annualizedVolatility, String reason) {

    public static VolatilityResult of(double annualizedVolatility) {
        return new VolatilityResult(true, annualizedVolatility, null);
    }

    public static VolatilityResult unavailable(String reason) {
        return new VolatilityResult(false, null, reason);
    }
}