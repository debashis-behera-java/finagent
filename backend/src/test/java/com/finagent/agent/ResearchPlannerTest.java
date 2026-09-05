package com.finagent.agent;

import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.PlannedStep;
import com.finagent.agent.model.ResearchPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchPlannerTest {

    private final ResearchPlanner planner = new ResearchPlanner();

    private static AgentContext ctx(String text, List<String> tickers) {
        return new AgentContext(text, tickers, null, null, null);
    }

    @Test
    void normalizesDedupesAndPlansFiveStepsPerTicker() {
        ResearchPlan plan = planner.plan(ctx("Research these", List.of("aapl", " MSFT ", "AAPL")));

        assertThat(plan.tickers()).containsExactly("AAPL", "MSFT");
        assertThat(plan.steps()).hasSize(10);
        List<PlannedStep> aapl = plan.steps().stream().filter(s -> s.ticker().equals("AAPL")).toList();
        assertThat(aapl).extracting(PlannedStep::stepType).containsExactly(
                PlannedStep.StepType.GET_PRICE,
                PlannedStep.StepType.GET_HISTORY,
                PlannedStep.StepType.GET_FUNDAMENTALS,
                PlannedStep.StepType.SEARCH_NEWS,
                PlannedStep.StepType.ANALYZE_RISK);
        assertThat(aapl).extracting(PlannedStep::toolName).containsExactly(
                "get_stock_price", "get_stock_history", "get_stock_fundamentals",
                "search_financial_news", "analyze_stock_risk");
    }

    @Test
    void extractsDollarPrefixedTickersFromFreeText() {
        ResearchPlan plan = planner.plan(ctx("Compare $AAPL and $MSFT for next quarter", List.of()));

        assertThat(plan.tickers()).containsExactly("AAPL", "MSFT");
    }

    @Test
    void ignoresBareEnglishWordsInFreeText() {
        assertThatThrownBy(() -> planner.plan(ctx("Compare AAPL and MSFT for next quarter", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No tickers");
    }

    @Test
    void rejectsBlankRequest() {
        assertThatThrownBy(() -> planner.plan(ctx("  ", List.of("AAPL"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    void rejectsMissingTickers() {
        // Shape-valid but unknown symbols pass planning (existence is checked at
        // execution time, where failures degrade into data gaps).
        ResearchPlan plan = planner.plan(ctx("research the market broadly", List.of("ZZZZ")));
        assertThat(plan.tickers()).containsExactly("ZZZZ");
    }

    @Test
    void rejectsRequestWithNoTickersAnywhere() {
        assertThatThrownBy(() -> planner.plan(ctx("tell me about the economy", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No tickers");
    }

    @Test
    void rejectsInvalidTicker() {
        assertThatThrownBy(() -> planner.plan(ctx("research", List.of("BAD!SYMBOL"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid symbol");
    }

    @Test
    void rejectsTooManyTickers() {
        List<String> many = List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11");
        assertThatThrownBy(() -> planner.plan(ctx("research", many)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many tickers");
    }
}
