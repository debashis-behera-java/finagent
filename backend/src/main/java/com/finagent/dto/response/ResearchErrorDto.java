package com.finagent.dto.response;

/** Failure payload for FAILED research runs. Safe message only — no stack traces. */
public record ResearchErrorDto(
        String code,
        String message) {
}
