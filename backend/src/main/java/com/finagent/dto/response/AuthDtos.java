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
            @Schema(description = "Always null since Task 3: the raw refresh token travels "
                    + "only in the HttpOnly finagent_rt cookie, never in JSON — "
                    + "retained as an explicit null so older clients fail visibly "
                    + "instead of misreading the shape") String refreshToken,
            @Schema(description = "Refresh-token lifetime in seconds (cookie Max-Age, not a secret)") long refreshExpiresIn,
            UserDto user) {

        public static AuthResponseDto bearer(String token, long expiresIn, UserDto user) {
            return new AuthResponseDto("Bearer", token, expiresIn, null, 0, user);
        }

        // NB (Task 3): there is deliberately NO factory that places a raw
        // refresh token into the JSON body. Cookie sessions use cookieSession()
        // below; the Set-Cookie header is the only transport for raw tokens.

        /**
         * Task 3: cookie-session response. The raw refresh token is delivered
         * ONLY via the {@code Set-Cookie} header — the JSON body carries an
         * explicit {@code null} so no JavaScript-accessible copy ever exists.
         * {@code refreshExpiresIn} stays (a lifetime is not a secret; it lets
         * clients reason about session expiry).
         */
        public static AuthResponseDto cookieSession(String token, long expiresIn,
                                                    long refreshExpiresIn, UserDto user) {
            return new AuthResponseDto("Bearer", token, expiresIn, null, refreshExpiresIn, user);
        }
    }

    /**
     * Task 4: aggregate refresh-session statistics (ADMIN only). Counts only —
     * never token values, never hashes, never secrets. {@code active} counts
     * currently usable tokens; {@code revoked} and {@code expired} are
     * independent counters (a row can be both), not a partition.
     * {@code lastCleanupAt} is null before the first cleanup run.
     */
    public record SessionStatsDto(
            @Schema(description = "Currently usable refresh tokens") long active,
            @Schema(description = "Tokens with revoked_at set (includes expired ones)") long revoked,
            @Schema(description = "Tokens past expires_at (includes revoked ones)") long expired,
            @Schema(description = "Distinct token families present") long families,
            @Schema(description = "Last cleanup run, null if never run") Instant lastCleanupAt,
            @Schema(description = "Rows removed by the last cleanup run") long lastCleanupDeleted) {
    }
}
