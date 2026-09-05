package com.finagent.dto.response;

import com.finagent.model.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 16: authentication responses. NEVER carries password material.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record UserDto(
            UUID id,
            String email,
            Role role,
            Instant createdAt) {
    }

    public record AuthResponseDto(
            @Schema(description = "Token type (always Bearer)") String tokenType,
            @Schema(description = "Signed JWT access token (short-lived)") String accessToken,
            @Schema(description = "Lifetime in seconds") long expiresIn,
            UserDto user) {

        public static AuthResponseDto bearer(String token, long expiresIn, UserDto user) {
            return new AuthResponseDto("Bearer", token, expiresIn, user);
        }
    }
}
