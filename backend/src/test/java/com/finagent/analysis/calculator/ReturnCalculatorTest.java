package com.finagent.analysis.calculator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReturnCalculatorTest {

    // Prices 100, 110, 105, 120, 115 => returns: 0.10, -0.0454545..., 0.142857..., -0.0416666...
    @Test
    void computesSimplePeriodicReturns() {
        List<BigDecimal> prices = List.of(
                new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("105"), new BigDecimal("120"), new BigDecimal("115"));

        List<Double> returns = ReturnCalculator.compute(prices);

        assertThat(returns).hasSize(4);
        assertThat(returns.get(0)).isCloseTo(0.10, within(1e-9));
        assertThat(returns.get(1)).isCloseTo(-0.045454545454545456, within(1e-9));
        assertThat(returns.get(2)).isCloseTo(0.14285714285714285, within(1e-9));
        assertThat(returns.get(3)).isCloseTo(-0.041666666666666664, within(1e-9));
    }

    @Test
    void twoPricesYieldOneReturn() {
        List<Double> returns = ReturnCalculator.compute(Arrays.asList(
                new BigDecimal("100"), new BigDecimal("110")));
        assertThat(returns).hasSize(1);
        assertThat(returns.get(0)).isCloseTo(0.10, within(1e-9));
    }

    @Test
    void emptyListYieldsEmptyReturns() {
        assertThat(ReturnCalculator.compute(List.of())).isEmpty();
    }

    @Test
    void singlePriceYieldsEmptyReturns() {
        assertThat(ReturnCalculator.compute(List.of(new BigDecimal("100")))).isEmpty();
    }

    @Test
    void nullListThrows() {
        assertThatThrownBy(() -> ReturnCalculator.compute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPriceThrows() {
        assertThatThrownBy(() -> ReturnCalculator.compute(
                Arrays.asList(new BigDecimal("100"), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void nonPositivePriceThrows() {
        assertThatThrownBy(() -> ReturnCalculator.compute(
                List.of(new BigDecimal("100"), new BigDecimal("0"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void negativePriceThrows() {
        assertThatThrownBy(() -> ReturnCalculator.compute(
                List.of(new BigDecimal("100"), new BigDecimal("-5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}