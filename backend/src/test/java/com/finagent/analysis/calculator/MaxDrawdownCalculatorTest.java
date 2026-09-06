package com.finagent.analysis.calculator;

import com.finagent.analysis.model.MaxDrawdownResult;
import com.finagent.analysis.model.PricePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class MaxDrawdownCalculatorTest {

    // 100 -> 110 (peak) -> 105 -> 120 (new peak) -> 115
    // max drawdown = (110 - 105)/110 = 0.0454545 (peak at 110, trough at 105)
    @Test
    void computesMaxDrawdownWithPeakAndTrough() {
        List<PricePoint> points = series(
                100, 110, 105, 120, 115);

        MaxDrawdownResult result = MaxDrawdownCalculator.compute(points);

        assertThat(result.available()).isTrue();
        assertThat(result.maxDrawdown()).isCloseTo(0.045454545454545456, within(1e-9));
        assertThat(result.peakValue()).isEqualByComparingTo(new BigDecimal("110"));
        assertThat(result.troughValue()).isEqualByComparingTo(new BigDecimal("105"));
    }

    // monotonically rising => drawdown 0
    @Test
    void risingSeriesHasZeroDrawdown() {
        MaxDrawdownResult result = MaxDrawdownCalculator.compute(series(100, 110, 120, 130));
        assertThat(result.available()).isTrue();
        assertThat(result.maxDrawdown()).isCloseTo(0.0, within(1e-12));
    }

    // 100 -> 80 => drawdown 0.20
    @Test
    void singleDeclineDrawdown() {
        MaxDrawdownResult result = MaxDrawdownCalculator.compute(series(100, 80));
        assertThat(result.available()).isTrue();
        assertThat(result.maxDrawdown()).isCloseTo(0.20, within(1e-9));
        assertThat(result.peakValue()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(result.troughValue()).isEqualByComparingTo(new BigDecimal("80"));
    }

    @Test
    void insufficientDataUnavailable() {
        assertThat(MaxDrawdownCalculator.compute(series(100)).available()).isFalse();
    }

    @Test
    void emptyListUnavailable() {
        assertThat(MaxDrawdownCalculator.compute(List.of()).available()).isFalse();
    }

    @Test
    void nullListThrows() {
        assertThatThrownBy(() -> MaxDrawdownCalculator.compute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPriceThrows() {
        List<PricePoint> points = Arrays.asList(new PricePoint(LocalDate.of(2026, 8, 1), null));
        assertThatThrownBy(() -> MaxDrawdownCalculator.compute(points))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDateThrows() {
        List<PricePoint> points = Arrays.asList(new PricePoint(null, new BigDecimal("100")));
        assertThatThrownBy(() -> MaxDrawdownCalculator.compute(points))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePriceThrows() {
        List<PricePoint> points = List.of(
                new PricePoint(LocalDate.of(2026, 8, 1), new BigDecimal("100")),
                new PricePoint(LocalDate.of(2026, 8, 2), new BigDecimal("0")));
        assertThatThrownBy(() -> MaxDrawdownCalculator.compute(points))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<PricePoint> series(double... prices) {
        LocalDate date = LocalDate.of(2026, 8, 1);
        PricePoint[] points = new PricePoint[prices.length];
        for (int i = 0; i < prices.length; i++) {
            points[i] = new PricePoint(date.plusDays(i), BigDecimal.valueOf(prices[i]).setScale(2));
        }
        return List.of(points);
    }
}