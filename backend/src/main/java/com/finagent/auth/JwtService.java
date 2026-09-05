package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Phase 16: signed JWT access tokens (HS256).
 *
 * <p>Claims are minimal: {@code sub} (user id), {@code email}, {@code role},
 * {@code iat}, {@code exp}. NEVER password hashes, API keys, secrets, or
 * research content. Validation is stateless (signature + expiry) — no database
 * hit per request; the filter reloads the user only to confirm it still exists.</p>
 */
@Component
public class JwtService {

    private final SecretKey signingKey;
    private final long ttlMillis;

    public JwtService(FinAgentProperties properties) {
        byte[] secret = properties.getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // Fail fast even outside prod: HS256 needs >= 256 bits.
            // Production additionally rejects placeholder values at startup.
            throw new IllegalStateException(
                    "JWT signing secret must be at least 32 bytes (configure FINAGENT_AUTH_JWT_SECRET)");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
        this.ttlMillis = properties.getAuth().getTokenTtl().toMillis();
    }

    /** Issue a short-lived access token for an authenticated user. */
    public String createToken(UUID userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(ttlMillis)))
                .signWith(signingKey)
                .compact();
    }

    /** Token lifetime in seconds (reported to clients as {@code expiresIn}). */
    public long expiresInSeconds() {
        return ttlMillis / 1000;
    }

    /** Parse + verify signature and expiry; throws {@link InvalidTokenException} otherwise. */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException("Access token has expired", ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid access token", ex);
        }
    }
}
