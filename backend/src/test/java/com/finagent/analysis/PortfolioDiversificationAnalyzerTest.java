package com.finagent.analysis;

import com.finagent.analysis.model.DiversificationResult;
import com.finagent.analysis.model.HoldingWeight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PortfolioDiversificationAnalyzerTest {

    // 4 equal 0.25 weights => HHI = 4 * 0.25^2 = 0.25, largest 0.25
    @Test
    void equalWeightsHaveLowConcentration() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(List.of(
                new HoldingWeight("AAPL", "Technology", 0.25),
                new HoldingWeight("MSFT", "Technology", 0.25),
                new HoldingWeight("JNJ", "Healthcare", 0.25),
                new HoldingWeight("XOM", "Energy", 0.25)));

        assertThat(result.available()).isTrue();
        assertThat(result.holdingCount()).isEqualTo(4);
        assertThat(result.herfindahlIndex()).isCloseTo(0.25, within(1e-9));
        assertThat(result.largestHoldingWeight()).isCloseTo(0.25, within(1e-9));
        assertThat(result.sectorCount()).isEqualTo(3);
        assertThat(result.sectorConcentration()).isCloseTo(0.375, within(1e-9)); // tech 0.5, health 0.25, energy 0.25
        assertThat(result.largestSectorWeight()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void singleHoldingFullyConcentrated() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(
                List.of(new HoldingWeight("AAPL", "Technology", 1.0)));
        assertThat(result.herfindahlIndex()).isCloseTo(1.0, within(1e-9));
        assertThat(result.largestHoldingWeight()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void weightsNormalizedWhenNotSummingToOne() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(List.of(
                new HoldingWeight("AAPL", null, 2.0),
                new HoldingWeight("MSFT", null, 3.0)));
        assertThat(result.rawWeightSum()).isCloseTo(5.0, within(1e-9));
        assertThat(result.normalized()).isTrue();
        assertThat(result.largestHoldingWeight()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void zeroWeightsExcluded() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(List.of(
                new HoldingWeight("AAPL", null, 1.0),
                new HoldingWeight("MSFT", null, 0.0),
                new HoldingWeight("JNJ", null, -1.0)));
        assertThat(result.holdingCount()).isEqualTo(1);
        assertThat(result.largestHoldingWeight()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void missingSectorGroupedAsUnknown() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(List.of(
                new HoldingWeight("AAPL", null, 0.5),
                new HoldingWeight("MSFT", "Technology", 0.5)));
        assertThat(result.unknownSectorCount()).isEqualTo(1);
        assertThat(result.sectorCount()).isEqualTo(2);
    }

    @Test
    void noPositiveWeightsUnavailable() {
        DiversificationResult result = PortfolioDiversificationAnalyzer.analyze(List.of(
                new HoldingWeight("AAPL", null, 0.0),
                new HoldingWeight("MSFT", null, 0.0)));
        assertThat(result.available()).isFalse();
    }

    @Test
    void emptyListUnavailable() {
        assertThat(PortfolioDiversificationAnalyzer.analyze(List.of()).available()).isFalse();
    }

    @Test
    void nullListThrows() {
        assertThatThrownBy(() -> PortfolioDiversificationAnalyzer.analyze(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTickerThrows() {
        assertThatThrownBy(() -> PortfolioDiversificationAnalyzer.analyze(
                List.of(new HoldingWeight("  ", null, 1.0))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}