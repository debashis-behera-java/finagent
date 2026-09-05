package com.finagent.agent.model;

import java.time.Instant;

/**
 * Outcome of one {@link com.finagent.agent.ResearchAgent} run: the validated facts, the
 * guarded LLM interpretation (ungrounded figures already redacted), and the guard verdict.
 * Persistence of this result (research_requests / research_results rows) arrives with the
 * orchestration phase — Phase 7 is in-memory only.
 */
public record AgentResult(
        FactBundle factBundle,
        String interpretation,
        GuardResult guardResult,
        Instant completedAt) {
}
