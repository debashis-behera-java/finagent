package com.finagent.agent.model;

/**
 * One step of a {@link ResearchPlan}: a typed data-collection action for a single ticker.
 *
 * <p>The {@code toolName} is always one of the MCP finance tools advertised by the FinAgent
 * MCP server (see {@code docs/mcp-tools.md}). The planner may only emit steps whose tool
 * is whitelisted in {@link com.finagent.agent.ToolSelector} — the registry is the
 * whitelist, so the agent can never invent a tool.</p>
 */
public record PlannedStep(
        StepType stepType,
        String ticker,
        String toolName) {

    /** Typed research actions. Each maps 1:1 to an MCP finance tool. */
    public enum StepType {
        GET_PRICE,
        GET_HISTORY,
        GET_FUNDAMENTALS,
        SEARCH_NEWS,
        ANALYZE_RISK
    }
}
