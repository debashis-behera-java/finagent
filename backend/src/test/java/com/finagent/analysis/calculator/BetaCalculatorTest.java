package com.finagent.analysis.calculator;

import com.finagent.analysis.model.BetaResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BetaCalculatorTest {

    // stock returns exactly 2x benchmark returns everywhere => beta = 2.0
    @Test
    void computesBetaFromAlignedReturns() {
        List<Double> stock = List.of(0.10, -0.05, 0.08, 0.12);
        List<Double> bench = List.of(0.05, -0.025, 0.04, 0.06);

        BetaResult result = BetaCalculator.compute(stock, bench, 2, "SPY");

        assertThat(result.available()).isTrue();
        assertThat(result.beta()).isCloseTo(2.0, within(1e-9));
        assertThat(result.benchmarkSymbol()).isEqualTo("SPY");
    }

    // identical series => beta = 1.0
    @Test
    void identicalSeriesBetaIsOne() {
        List<Double> r = List.of(0.01, -0.02, 0.03);
        BetaResult result = BetaCalculator.compute(r, r, 2, "SPY");
        assertThat(result.beta()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void insufficientObservationsUnavailable() {
        BetaResult result = BetaCalculator.compute(List.of(0.10), List.of(0.05), 2, "SPY");
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("at least");
    }

    @Test
    void zeroBenchmarkVarianceUnavailable() {
        // constant benchmark returns => variance 0
        BetaResult result = BetaCalculator.compute(
                List.of(0.10, 0.20), List.of(0.05, 0.05), 2, "SPY");
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("zero");
    }

    @Test
    void mismatchedLengthsThrow() {
        assertThatThrownBy(() -> BetaCalculator.compute(
                List.of(0.10, 0.20), List.of(0.05), 2, "SPY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aligned");
    }

    @Test
    void nullStockThrows() {
        assertThatThrownBy(() -> BetaCalculator.compute(null, List.of(0.05), 2, "SPY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minObservationsBelowOneThrows() {
        assertThatThrownBy(() -> BetaCalculator.compute(
                List.of(0.10, 0.20), List.of(0.05, 0.10), 0, "SPY"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}