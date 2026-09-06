package com.finagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Task 2: refresh / logout payload. Carries the raw refresh token (use once). */
public record RefreshRequest(
        @NotBlank @Size(max = 512)
        @Schema(description = "Raw refresh token issued by login, register, or a previous refresh",
                example = "REDACTED")
        String refreshToken) {
}
