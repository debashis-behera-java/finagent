package com.finagent.agent;

import com.finagent.agent.model.PlannedStep;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Maps plan steps to concrete MCP tools (Phase 7).
 *
 * <p>This is the <b>registry whitelist</b>: the only tool names the agent may ever select
 * are the finance tools advertised by the FinAgent MCP server. Any step type without a
 * mapping is rejected with {@link IllegalArgumentException} instead of falling back to
 * an invented tool — selection is registry-constrained by construction.</p>
 */
@Component
public class ToolSelector {

    public static final String GET_STOCK_PRICE = "get_stock_price";
    public static final String GET_STOCK_HISTORY = "get_stock_history";
    public static final String GET_STOCK_FUNDAMENTALS = "get_stock_fundamentals";
    public static final String SEARCH_FINANCIAL_NEWS = "search_financial_news";
    public static final String ANALYZE_STOCK_RISK = "analyze_stock_risk";

    private static final Map<PlannedStep.StepType, String> TOOL_BY_STEP = new EnumMap<>(PlannedStep.StepType.class);

    static {
        TOOL_BY_STEP.put(PlannedStep.StepType.GET_PRICE, GET_STOCK_PRICE);
        TOOL_BY_STEP.put(PlannedStep.StepType.GET_HISTORY, GET_STOCK_HISTORY);
        TOOL_BY_STEP.put(PlannedStep.StepType.GET_FUNDAMENTALS, GET_STOCK_FUNDAMENTALS);
        TOOL_BY_STEP.put(PlannedStep.StepType.SEARCH_NEWS, SEARCH_FINANCIAL_NEWS);
        TOOL_BY_STEP.put(PlannedStep.StepType.ANALYZE_RISK, ANALYZE_STOCK_RISK);
    }

    /** Resolve the MCP tool name for a planned step; rejects unknown step types. */
    public String toolFor(PlannedStep step) {
        if (step == null || step.stepType() == null) {
            throw new IllegalArgumentException("Planned step and step type must not be null");
        }
        String tool = TOOL_BY_STEP.get(step.stepType());
        if (tool == null) {
            throw new IllegalArgumentException("No MCP tool registered for step type: " + step.stepType());
        }
        return tool;
    }

    /** All tools the agent is allowed to select, in canonical order. */
    public List<String> availableTools() {
        return List.of(GET_STOCK_PRICE, GET_STOCK_HISTORY, GET_STOCK_FUNDAMENTALS,
                SEARCH_FINANCIAL_NEWS, ANALYZE_STOCK_RISK);
    }
}
