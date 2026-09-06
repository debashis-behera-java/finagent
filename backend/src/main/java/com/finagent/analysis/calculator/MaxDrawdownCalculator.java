package com.finagent.analysis.calculator;

import com.finagent.analysis.model.MaxDrawdownResult;
import com.finagent.analysis.model.PricePoint;

import java.math.BigDecimal;
import java.util.List;

/**
 * Maximum drawdown calculation over chronological price observations.
 *
 * <p>Formula (documented in docs/risk-methodology.md): for each observation t,
 * with {@code peak_t = max(P_1..P_t)}:</p>
 * <pre>
 *   DD_t = (peak_t - P_t) / peak_t        (0 when P_t == peak_t)
 *   MaxDrawdown = max(DD_t)
 * </pre>
 * Reported as a positive fraction together with the peak value/date and trough
 * value/date of the drawdown episode. Requires at least two observations; null
 * or non-positive prices are invalid input.
 */
public final class MaxDrawdownCalculator {

    private MaxDrawdownCalculator() {
    }

    /**
     * @param points chronological price observations (dates ascending, prices positive)
     * @return the maximum drawdown result (or {@link MaxDrawdownResult#unavailable}
     *         when fewer than two observations are supplied)
     * @throws IllegalArgumentException if {@code points} is null or contains
     *                                  null dates / null or non-positive prices
     */
    public static MaxDrawdownResult compute(List<PricePoint> points) {
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
        // Validate data integrity up front so a single null element is rejected
        // even when the series is too short for a meaningful drawdown.
        for (PricePoint point : points) {
            if (point.date() == null) {
                throw new IllegalArgumentException("price point with null date");
            }
            if (point.price() == null) {
                throw new IllegalArgumentException("price point on " + point.date() + " has a null price");
            }
            if (point.price().signum() <= 0) {
                throw new IllegalArgumentException(
                        "price on " + point.date() + " must be positive, was " + point.price());
            }
        }
        if (points.size() < 2) {
            return MaxDrawdownResult.unavailable(
                    "Insufficient data: need at least 2 price observations, got " + points.size());
        }

        BigDecimal runningPeak = null;
        PricePoint peakPoint = null;
        double maxDrawdown = 0.0;
        PricePoint maxPeak = null;
        PricePoint maxTrough = null;

        for (PricePoint point : points) {
            if (runningPeak == null || point.price().compareTo(runningPeak) > 0) {
                runningPeak = point.price();
                peakPoint = point;
            }
            if (runningPeak.signum() == 0) {
                continue;
            }
            double drawdown = runningPeak.subtract(point.price())
                    .divide(runningPeak, java.math.MathContext.DECIMAL64)
                    .doubleValue();
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown;
                maxPeak = peakPoint;
                maxTrough = point;
            }
        }

        // A non-declining series has a valid max drawdown of 0 (peak == trough at the highest point).
        if (maxPeak == null) {
            maxPeak = peakPoint;
            maxTrough = peakPoint;
        }
        return MaxDrawdownResult.of(maxDrawdown,
                maxPeak.price(), maxPeak.date(), maxTrough.price(), maxTrough.date());
    }
}