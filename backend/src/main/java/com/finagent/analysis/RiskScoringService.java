package com.finagent.analysis;

import com.finagent.analysis.model.RiskCategory;
import com.finagent.analysis.model.RiskMetrics;
import com.finagent.analysis.model.RiskScore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, transparent composite risk scoring.
 *
 * <p>Methodology (documented in docs/risk-methodology.md; all thresholds
 * constant and published):</p>
 * <ul>
 *   <li>Each available metric is normalized to a 0..1 "risk component":</li>
 *   <li>volatility:  min(annualizedVolatility / 0.30, 1)      (30% annualized = high risk)</li>
 *   <li>drawdown:    min(maxDrawdown / 0.50, 1)               (50% drawdown = high risk)</li>
 *   <li>beta:        beta &lt; 0 → 0; else min(beta / 2.0, 1)   (beta 2 = high risk)</li>
 *   <li>sharpe:      clamp((1 - sharpe) / 3, 0, 1)            (sharpe 1 = low, -2 = high)</li>
 *   <li>concentration: HHI of portfolio weights (0..1 as-is)</li>
 * </ul>
 * <p>Weights (relative): volatility 0.30, drawdown 0.25, beta 0.15, sharpe 0.15,
 * concentration 0.15. When a metric is unavailable its weight is re-distributed
 * proportionally among the available metrics, and the omission is recorded in
 * the explanation - a score is never produced from zero metrics.</p>
 * <p>Composite score (0..100) = weighted sum of components. Category:
 * [0, 33.33) LOW, [33.33, 66.66] MODERATE, (66.66, 100] HIGH.</p>
 */
public final class RiskScoringService {

    public static final String VOLATILITY = "volatility";
    public static final String MAX_DRAWDOWN = "maxDrawdown";
    public static final String BETA = "beta";
    public static final String SHARPE = "sharpe";
    public static final String CONCENTRATION = "concentration";

    private static final double LOW_BOUND = 33.33;
    private static final double HIGH_BOUND = 66.66;

    /** Relative weights per metric. */
    private static final Map<String, Double> WEIGHTS = Map.of(
            VOLATILITY, 0.30,
            MAX_DRAWDOWN, 0.25,
            BETA, 0.15,
            SHARPE, 0.15,
            CONCENTRATION, 0.15);

    private RiskScoringService() {
    }

    /**
     * Computes the composite risk score from the supplied metrics.
     *
     * @param metrics risk metrics; unavailable metrics must be {@code null}
     * @return the risk score
     * @throws IllegalArgumentException if {@code metrics} is null
     */
    public static RiskScore score(RiskMetrics metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }

        Map<String, Double> components = new LinkedHashMap<>();
        components.put(VOLATILITY, volatilityComponent(metrics.annualizedVolatility()));
        components.put(MAX_DRAWDOWN, drawdownComponent(metrics.maxDrawdown()));
        components.put(BETA, betaComponent(metrics.beta()));
        components.put(SHARPE, sharpeComponent(metrics.sharpeRatio()));
        components.put(CONCENTRATION, concentrationComponent(metrics.concentration()));
        components.values().removeIf(java.util.Objects::isNull);

        if (components.isEmpty()) {
            return RiskScore.unavailable("No risk metrics available");
        }

        double totalWeight = components.keySet().stream().mapToDouble(WEIGHTS::get).sum();
        double composite = 0.0;
        Map<String, Double> contributions = new LinkedHashMap<>();
        List<String> explanation = new ArrayList<>();
        for (Map.Entry<String, Double> entry : components.entrySet()) {
            double normalizedWeight = WEIGHTS.get(entry.getKey()) / totalWeight;
            double contribution = normalizedWeight * entry.getValue();
            composite += contribution;
            contributions.put(entry.getKey(), Math.round(entry.getValue() * 1000.0) / 1000.0);
            explanation.add(explanationLine(entry.getKey(), entry.getValue(), normalizedWeight));
        }
        if (metrics.unavailableCount() > 0) {
            explanation.add(metrics.unavailableCount() + " of 5 metrics unavailable - weight re-normalized among available metrics");
        }

        double score = Math.round(composite * 100.0 * 100.0) / 100.0;
        return RiskScore.of(score, category(score), contributions, explanation);
    }

    private static Double volatilityComponent(Double value) {
        return value == null ? null : Math.min(Math.max(value / 0.30, 0.0), 1.0);
    }

    private static Double drawdownComponent(Double value) {
        return value == null ? null : Math.min(Math.max(value / 0.50, 0.0), 1.0);
    }

    private static Double betaComponent(Double value) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            return 0.0;
        }
        return Math.min(value / 2.0, 1.0);
    }

    private static Double sharpeComponent(Double value) {
        if (value == null) {
            return null;
        }
        double component = (1.0 - value) / 3.0;
        return Math.min(Math.max(component, 0.0), 1.0);
    }

    private static Double concentrationComponent(Double value) {
        return value == null ? null : Math.min(Math.max(value, 0.0), 1.0);
    }

    private static String explanationLine(String metric, double component, double weight) {
        return metric + " component=" + round(component) + " (weight " + round(weight) + ")";
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** @return LOW / MODERATE / HIGH with the documented thresholds. */
    public static RiskCategory category(double score) {
        if (score < LOW_BOUND) {
            return RiskCategory.LOW;
        }
        if (score <= HIGH_BOUND) {
            return RiskCategory.MODERATE;
        }
        return RiskCategory.HIGH;
    }
}