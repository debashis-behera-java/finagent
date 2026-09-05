package com.finagent.agent.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Input to a single {@link com.finagent.agent.ResearchAgent} run: the user's free-text
 * request plus the resolved research parameters. Tickers may be empty — the planner then
 * attempts to extract candidate symbols from the request text.
 */
public record AgentContext(
        String requestText,
        List<String> tickers,
        LocalDate from,
        LocalDate to,
        String benchmark) {

    public AgentContext {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
    }
}
