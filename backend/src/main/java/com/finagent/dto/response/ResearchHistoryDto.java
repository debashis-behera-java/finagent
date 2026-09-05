package com.finagent.dto.response;

import java.util.List;

/** GET /api/v1/research paged history, newest first. */
public record ResearchHistoryDto(
        List<ResearchStatusDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
