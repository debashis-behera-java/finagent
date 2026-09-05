package com.finagent.agent;

import com.finagent.agent.model.PlannedStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSelectorTest {

    private final ToolSelector selector = new ToolSelector();

    @Test
    void mapsEveryStepTypeToItsMcpTool() {
        assertThat(selector.toolFor(step(PlannedStep.StepType.GET_PRICE))).isEqualTo("get_stock_price");
        assertThat(selector.toolFor(step(PlannedStep.StepType.GET_HISTORY))).isEqualTo("get_stock_history");
        assertThat(selector.toolFor(step(PlannedStep.StepType.GET_FUNDAMENTALS))).isEqualTo("get_stock_fundamentals");
        assertThat(selector.toolFor(step(PlannedStep.StepType.SEARCH_NEWS))).isEqualTo("search_financial_news");
        assertThat(selector.toolFor(step(PlannedStep.StepType.ANALYZE_RISK))).isEqualTo("analyze_stock_risk");
    }

    @Test
    void advertisesExactlyTheFiveFinanceTools() {
        assertThat(selector.availableTools()).containsExactly(
                "get_stock_price", "get_stock_history", "get_stock_fundamentals",
                "search_financial_news", "analyze_stock_risk");
    }

    @Test
    void rejectsNullStepAndNullType() {
        assertThatThrownBy(() -> selector.toolFor(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selector.toolFor(new PlannedStep(null, "AAPL", "get_stock_price")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PlannedStep step(PlannedStep.StepType type) {
        return new PlannedStep(type, "AAPL", "ignored-here");
    }
}
