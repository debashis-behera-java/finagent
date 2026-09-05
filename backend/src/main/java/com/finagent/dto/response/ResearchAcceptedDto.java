package com.finagent.dto.response;

import java.time.Instant;
import java.util.UUID;

/** POST /api/v1/research response: 202 Accepted with the persisted PENDING job. */
public record ResearchAcceptedDto(
        UUID researchId,
        String status,
        Instant createdAt) {
}
