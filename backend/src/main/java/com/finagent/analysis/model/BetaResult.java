package com.finagent.analysis.model;

/**
 * Beta result relative to a benchmark. {@code available=false} means beta
 * could not be estimated (insufficient aligned data, zero benchmark variance,
 * missing benchmark data) - see {@code reason}.
 */
public record BetaResult(boolean available, Double beta, String benchmarkSymbol, String reason) {

    public static BetaResult of(double beta, String benchmarkSymbol) {
        return new BetaResult(true, beta, benchmarkSymbol, null);
    }

    public static BetaResult unavailable(String reason, String benchmarkSymbol) {
        return new BetaResult(false, null, benchmarkSymbol, reason);
    }
}