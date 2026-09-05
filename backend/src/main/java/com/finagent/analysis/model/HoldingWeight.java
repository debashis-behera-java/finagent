package com.finagent.analysis.model;

/**
 * One portfolio position used by the deterministic diversification analyzer.
 * {@code sector} may be {@code null} when sector information is unavailable -
 * it is NOT invented (reported under an "UNKNOWN" group instead).
 */
public record HoldingWeight(String ticker, String sector, double weight) {
}