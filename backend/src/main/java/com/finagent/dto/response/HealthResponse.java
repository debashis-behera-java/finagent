package com.finagent.dto.response;

/**
 * Response body of GET /api/v1/health.
 */
public record HealthResponse(
        String application,
        String version,
        String status
) {
}
