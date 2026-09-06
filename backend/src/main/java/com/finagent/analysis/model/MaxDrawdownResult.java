package com.finagent.analysis.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Maximum-drawdown result. The drawdown is reported as a positive fraction
 * (e.g. {@code 0.1842} == 18.42%). Peak/trough value and date are included
 * when the series contains enough observations.
 */
public record MaxDrawdownResult(
        boolean available,
        Double maxDrawdown,
        BigDecimal peakValue,
        LocalDate peakDate,
        BigDecimal troughValue,
        LocalDate troughDate,
        String reason) {

    public static MaxDrawdownResult of(double maxDrawdown,
                                       BigDecimal peakValue, LocalDate peakDate,
                                       BigDecimal troughValue, LocalDate troughDate) {
        return new MaxDrawdownResult(true, maxDrawdown, peakValue, peakDate, troughValue, troughDate, null);
    }

    public static MaxDrawdownResult unavailable(String reason) {
        return new MaxDrawdownResult(false, null, null, null, null, null, reason);
    }
}