package com.finagent.agent.model;

import java.util.List;

/**
 * Deterministic research plan: the tickers under research and the ordered data-collection
 * steps that cover them. Built by {@link com.finagent.agent.ResearchPlanner} without any
 * LLM involvement (rule-based, constrained to the known MCP tool list), so planning is
 * fully reproducible and unit-testable. LLM-assisted planning may refine — never widen —
 * this plan in a later phase.
 */
public record ResearchPlan(
        List<String> tickers,
        List<PlannedStep> steps) {

    public ResearchPlan {
        tickers = List.copyOf(tickers);
        steps = List.copyOf(steps);
    }
}
