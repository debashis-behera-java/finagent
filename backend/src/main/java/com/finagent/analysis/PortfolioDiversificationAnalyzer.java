package com.finagent.analysis;

import com.finagent.analysis.model.DiversificationResult;
import com.finagent.analysis.model.HoldingWeight;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic portfolio diversification analysis.
 *
 * <p>Methodology (documented in docs/risk-methodology.md):</p>
 * <ul>
 *   <li>Non-positive/zero-weight holdings are excluded (reported implicitly by
 *       {@code holdingCount} and {@code rawWeightSum}).</li>
 *   <li>Weights are normalized: {@code w'_i = w_i / sum(w)} so they sum to 1;
 *       {@code normalized=true} when the raw sum != 1.</li>
 *   <li>Largest holding = max normalized weight.</li>
 *   <li>Herfindahl-Hirschman concentration index {@code HHI = sum(w'_i^2)} (0..1:
 *       1 = a single position, lower = more diversified).</li>
 *   <li>Sector concentration uses the same HHI over grouped sector weights.
 *       Missing sectors are grouped under "UNKNOWN" and {@code unknownSectorCount}
 *       is reported - sector information is never invented.</li>
 * </ul>
 */
public final class PortfolioDiversificationAnalyzer {

    private static final String UNKNOWN_SECTOR = "UNKNOWN";

    private PortfolioDiversificationAnalyzer() {
    }

    /**
     * @param holdings portfolio positions (ticker, optional sector, weight)
     * @return the diversification result
     * @throws IllegalArgumentException if {@code holdings} is null
     */
    public static DiversificationResult analyze(List<HoldingWeight> holdings) {
        if (holdings == null) {
            throw new IllegalArgumentException("holdings must not be null");
        }

        List<HoldingWeight> valid = new ArrayList<>();
        double rawSum = 0.0;
        for (HoldingWeight holding : holdings) {
            if (holding.ticker() == null || holding.ticker().isBlank()) {
                throw new IllegalArgumentException("holding with blank ticker");
            }
            if (holding.weight() > 0) {
                valid.add(holding);
                rawSum += holding.weight();
            }
        }

        if (valid.isEmpty() || rawSum <= 0) {
            return DiversificationResult.unavailable("No positive-weight holdings to analyze");
        }

        // Normalize weights.
        List<HoldingWeight> normalized = new ArrayList<>(valid.size());
        Map<String, Double> sectorWeights = new LinkedHashMap<>();
        int unknownSectorCount = 0;
        for (HoldingWeight holding : valid) {
            double w = holding.weight() / rawSum;
            normalized.add(new HoldingWeight(holding.ticker(), holding.sector(), w));
            String sector = (holding.sector() == null || holding.sector().isBlank())
                    ? UNKNOWN_SECTOR : holding.sector().trim();
            if (UNKNOWN_SECTOR.equals(sector)) {
                unknownSectorCount++;
            }
            sectorWeights.merge(sector, w, Double::sum);
        }

        double hhi = normalized.stream().mapToDouble(h -> h.weight() * h.weight()).sum();
        double largestHolding = normalized.stream().mapToDouble(HoldingWeight::weight).max().orElse(0.0);

        double sectorHhi = sectorWeights.values().stream().mapToDouble(w -> w * w).sum();
        double largestSector = sectorWeights.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);

        return new DiversificationResult(
                true,
                null,
                normalized.size(),
                rawSum,
                Math.abs(rawSum - 1.0) > 1e-9,
                largestHolding,
                hhi,
                sectorHhi,
                sectorWeights.size(),
                largestSector,
                unknownSectorCount,
                normalized);
    }
}