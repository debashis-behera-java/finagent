package com.finagent.analysis.model;

import java.util.Map;
import java.util.List;

/**
 * Deterministic composite risk score (0-100) with category, the per-metric
 * contributions (0-1 each, weight-normalized when metrics are missing) and a
 * human-readable explanation. Never computed by an LLM.
 */
public record RiskScore(
        boolean available,
        Double score,
        RiskCategory category,
        Map<String, Double> contributingMetrics,
        List<String> explanation,
        String reason) {

    public static RiskScore of(double score, RiskCategory category,
                               Map<String, Double> contributingMetrics,
                               List<String> explanation) {
        return new RiskScore(true, score, category,
                Map.copyOf(contributingMetrics), List.copyOf(explanation), null);
    }

    public static RiskScore unavailable(String reason) {
        return new RiskScore(false, null, null, Map.of(), List.of(), reason);
    }
}