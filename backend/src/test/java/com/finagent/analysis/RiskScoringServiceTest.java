package com.finagent.analysis;

import com.finagent.analysis.model.RiskCategory;
import com.finagent.analysis.model.RiskMetrics;
import com.finagent.analysis.model.RiskScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RiskScoringServiceTest {

    // All metrics moderate: vol 0.15 (0.5), dd 0.20 (0.4), beta 1.0 (0.5), sharpe 0.5 (0.1667), conc 0.5 (0.5)
    @Test
    void computesCompositeScoreAndCategory() {
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.15, 0.20, 1.0, 0.5, 0.5));

        assertThat(score.available()).isTrue();
        // weighted: 0.30*0.5 + 0.25*0.4 + 0.15*0.5 + 0.15*0.1667 + 0.15*0.5 = 0.15+0.10+0.075+0.025+0.075=0.425 => 42.5 => MODERATE
        assertThat(score.score()).isCloseTo(42.50, within(1e-9));
        assertThat(score.category()).isEqualTo(RiskCategory.MODERATE);
        assertThat(score.contributingMetrics()).hasSize(5);
        assertThat(score.explanation()).isNotEmpty();
    }

    @Test
    void highRiskCategoryForExtremeMetrics() {
        // all components maxed (vol 0.6 =>1, dd 0.8=>1, beta 3=>1, sharpe -2=>1, conc 1=>1)
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.6, 0.8, 3.0, -2.0, 1.0));
        assertThat(score.category()).isEqualTo(RiskCategory.HIGH);
        assertThat(score.score()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    void lowRiskCategoryForCalmMetrics() {
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.05, 0.02, 0.3, 2.0, 0.1));
        assertThat(score.category()).isEqualTo(RiskCategory.LOW);
        assertThat(score.score()).isLessThan(33.33);
    }

    @Test
    void boundaryLowIsModerate() {
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.10, 0.0, 0.0, 0.0, 0.0));
        // 0.30*0.3333 + 0.25*0 + 0.15*0 + 0.15*0.3333 + 0.15*0.1 = 0.1+0.05+0.015 = 0.165 => 16.5 LOW
        assertThat(score.category()).isEqualTo(RiskCategory.LOW);
    }

    @Test
    void exactlyHighBoundary() {
        // vol = 0.30 => component 1.0, rest 0 => 0.30 contribution => 30 score => MODERATE
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.30, 0.0, 0.0, 0.0, 0.0));
        assertThat(score.category()).isEqualTo(RiskCategory.MODERATE);
    }

    @Test
    void missingMetricsReNormalizeWeights() {
        // only volatility available (component 0.5); score should be 0.5*100 = 50 MODERATE
        RiskScore score = RiskScoringService.score(new RiskMetrics(0.15, null, null, null, null));
        assertThat(score.available()).isTrue();
        assertThat(score.score()).isCloseTo(50.0, within(1e-9));
        assertThat(score.explanation()).anyMatch(line -> line.contains("unavailable"));
    }

    @Test
    void noMetricsUnavailable() {
        RiskScore score = RiskScoringService.score(new RiskMetrics(null, null, null, null, null));
        assertThat(score.available()).isFalse();
        assertThat(score.reason()).contains("No risk metrics");
    }

    @Test
    void nullMetricsThrows() {
        assertThatThrownBy(() -> RiskScoringService.score(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void betaNegativeTreatedAsLowRiskComponent() {
        RiskScore score = RiskScoringService.score(new RiskMetrics(null, null, -1.5, null, null));
        assertThat(score.available()).isTrue();
        // only beta available, component 0 (negative beta) => 0 => LOW
        assertThat(score.category()).isEqualTo(RiskCategory.LOW);
        assertThat(score.score()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void categoriesPerThresholds() {
        assertThat(RiskScoringService.category(0.0)).isEqualTo(RiskCategory.LOW);
        assertThat(RiskScoringService.category(33.33)).isEqualTo(RiskCategory.MODERATE);
        assertThat(RiskScoringService.category(50.0)).isEqualTo(RiskCategory.MODERATE);
        assertThat(RiskScoringService.category(66.66)).isEqualTo(RiskCategory.MODERATE);
        assertThat(RiskScoringService.category(66.67)).isEqualTo(RiskCategory.HIGH);
        assertThat(RiskScoringService.category(100.0)).isEqualTo(RiskCategory.HIGH);
    }
}