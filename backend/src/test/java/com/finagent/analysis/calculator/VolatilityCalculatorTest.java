package com.finagent.analysis.calculator;

import com.finagent.analysis.model.VolatilityResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class VolatilityCalculatorTest {

    // returns [0.10, -0.045454545454545456, 0.14285714285714285, -0.041666666666666664]
    // sample stdev ~0.0968624; * sqrt(252) ~1.53764
    @Test
    void annualizesFromDailyReturns() {
        List<Double> returns = List.of(0.10, -0.045454545454545456, 0.14285714285714285, -0.041666666666666664);

        VolatilityResult result = VolatilityCalculator.annualized(returns, 252);

        assertThat(result.available()).isTrue();
        assertThat(result.annualizedVolatility()).isCloseTo(1.53764, within(1e-3));
    }

    @Test
    void customAnnualizationFactor() {
        List<Double> returns = List.of(0.01, -0.01, 0.02, -0.02);
        VolatilityResult r12 = VolatilityCalculator.annualized(returns, 12);
        VolatilityResult r252 = VolatilityCalculator.annualized(returns, 252);
        // monthly (12) should be ~sqrt(252/12) ~ 4.58x smaller than daily annualization
        assertThat(r252.annualizedVolatility()).isCloseTo(r12.annualizedVolatility() * Math.sqrt(252.0 / 12.0), within(1e-9));
    }

    @Test
    void insufficientObservationsUnavailable() {
        VolatilityResult result = VolatilityCalculator.annualized(List.of(0.10), 252);
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).contains("at least 2");
    }

    @Test
    void emptyListUnavailable() {
        assertThat(VolatilityCalculator.annualized(List.of(), 252).available()).isFalse();
    }

    @Test
    void zeroVarianceIsZeroVolatility() {
        VolatilityResult result = VolatilityCalculator.annualized(List.of(0.01, 0.01, 0.01), 252);
        assertThat(result.available()).isTrue();
        assertThat(result.annualizedVolatility()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    void nullListThrows() {
        assertThatThrownBy(() -> VolatilityCalculator.annualized(null, 252))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveAnnualizationFactorThrows() {
        assertThatThrownBy(() -> VolatilityCalculator.annualized(List.of(0.01, 0.02), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}