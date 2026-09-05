package com.finagent.analysis.model;

/**
 * Input bundle for the composite risk score. Each field may be {@code null}
 * when the underlying metric is unavailable - the scorer only uses the metrics
 * it is given and never fabricates values.
 */
public record RiskMetrics(
        Double annualizedVolatility,
        Double maxDrawdown,
        Double beta,
        Double sharpeRatio,
        Double concentration
) {

    /** Number of metrics not supplied (null fields) - used to explain weight re-normalization. */
    public int unavailableCount() {
        return (int) java.util.stream.Stream.of(annualizedVolatility, maxDrawdown, beta, sharpeRatio, concentration)
                .filter(java.util.Objects::isNull)
                .count();
    }
}