package com.finagent.analysis.calculator;

import com.finagent.analysis.model.SharpeRatioResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SharpeRatioCalculatorTest {

    // daily returns 0.01, -0.01, 0.02, -0.02: mean 0 => annualized return 0
    // sample stdev ~0.0182574; * sqrt(252) ~0.28983; Sharpe = (0 - 0.0425)/0.28983 = -0.14664
    @Test
    void computesAnnualizedSharpeRatio() {
        List<Double> returns = List.of(0.01, -0.01, 0.02, -0.02);

        SharpeRatioResult result = SharpeRatioCalculator.compute(returns, 252, 0.0425);

        assertThat(result.available()).isTrue();
        assertThat(result.sharpeRatio()).isCloseTo(-0.14664, within(1e-4));
        assertThat(result.riskFreeRate()).isCloseTo(0.0425, within(1e-9));
        assertThat(result.annualizedReturn()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void positiveSharpeForExcessReturn() {
        // constant daily 0.001 => annualized return 0.252, vol 0 => unavailable (zero variance)
        SharpeRatioResult zeroVol = SharpeRatioCalculator.compute(List.of(0.001, 0.001), 252, 0.04);
        assertThat(zeroVol.available()).isFalse();
        assertThat(zeroVol.reason()).contains("zero");
    }

    @Test
    void missingRiskFreeRateUnavailable() {
        SharpeRatioResult result = SharpeRatioCalculator.compute(List.of(0.01, 0.02), 252, null);
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("risk-free");
    }

    @Test
    void insufficientObservationsUnavailable() {
        assertThat(SharpeRatioCalculator.compute(List.of(0.01), 252, 0.04).available()).isFalse();
    }

    @Test
    void emptyListUnavailable() {
        assertThat(SharpeRatioCalculator.compute(List.of(), 252, 0.04).available()).isFalse();
    }

    @Test
    void nullListThrows() {
        assertThatThrownBy(() -> SharpeRatioCalculator.compute(null, 252, 0.04))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveAnnualizationThrows() {
        assertThatThrownBy(() -> SharpeRatioCalculator.compute(List.of(0.01, 0.02), 0, 0.04))
                .isInstanceOf(IllegalArgumentException.class);
    }
}