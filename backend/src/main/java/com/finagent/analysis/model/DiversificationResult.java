package com.finagent.analysis.model;

import java.util.List;

/**
 * Deterministic portfolio diversification analysis. Weights are normalized to
 * sum to 1 (the raw sum is reported); non-positive weights are excluded.
 * Sector metrics are computed from the sector labels actually provided.
 */
public record DiversificationResult(
        boolean available,
        String reason,
        int holdingCount,
        double rawWeightSum,
        boolean normalized,
        double largestHoldingWeight,
        double herfindahlIndex,
        Double sectorConcentration,
        Integer sectorCount,
        Double largestSectorWeight,
        int unknownSectorCount,
        List<HoldingWeight> holdings) {

    public static DiversificationResult unavailable(String reason) {
        return new DiversificationResult(false, reason, 0, 0, false, 0, 0,
                null, null, null, 0, List.of());
    }
}