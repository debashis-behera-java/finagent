package com.finagent.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /api/v1/research/{id} response and GET /api/v1/research history item.
 * {@code result} is present only for COMPLETED runs, {@code error} only for FAILED.
 */
public record ResearchStatusDto(
        UUID researchId,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String requestText,
        List<String> tickers,
        ResearchResultDto result,
        ResearchErrorDto error) {
}
