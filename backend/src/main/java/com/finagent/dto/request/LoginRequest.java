package com.finagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Phase 16: login payload. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(description = "Account email", example = "analyst@example.com")
        String email,

        @NotBlank @Size(max = 72)
        @Schema(description = "Account password")
        String password) {
}
