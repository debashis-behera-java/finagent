package com.finagent.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * Persisted structured result of a COMPLETED research run.
 *
 * <p>Numbers come from the deterministic metrics snapshot; {@code interpretation} is
 * the guarded LLM prose. No raw prompts, no model chain-of-thought, no secrets.</p>
 */
public record ResearchResultDto(
        List<String> tickers,
        String executiveSummary,
        String interpretation,
        JsonNode metrics,
        String newsSummary,
        String disclaimer,
        Instant completedAt) {
}
