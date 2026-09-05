package com.finagent.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Phase 16: registration payload. No role field exists by design — every
 * registration creates a USER; ADMIN is assigned out-of-band only.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254)
        @Schema(description = "Account email (unique, case-insensitive)", example = "analyst@example.com")
        String email,

        @NotBlank @Size(min = 8, max = 72)
        @Schema(description = "Password: 8-72 chars, at least one letter and one digit")
        String password) {
}
