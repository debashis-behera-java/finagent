package com.finagent.auth;

import com.finagent.config.FinAgentProperties;
import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.model.RefreshToken;
import com.finagent.model.User;
import com.finagent.repository.RefreshTokenRepository;
import com.finagent.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Task 2: database-backed refresh-token rotation for the Phase 16 JWT boundary.
 *
 * <p>Protocol (strict rotation): a refresh token is usable exactly once. A successful
 * {@code POST /api/v1/auth/refresh} marks the presented token used, mints one
 * replacement in the same family, and returns it with a fresh access JWT. Any later
 * presentation of the consumed token is <b>reuse</b>: the request is rejected and the
 * entire family is revoked (possible theft signal). Logout revokes the whole family,
 * ending the session chain. Stateless access JWTs cannot be revoked — they simply
 * expire (1h default); revocation applies to refresh tokens only.</p>
 *
 * <p>Storage: only the SHA-256 hex hash is persisted — raw tokens exist solely in
 * the issuance response. Token secrets come from {@link SecureRandom} (256 bits);
 * UUIDs are row/family identifiers only, never secret material. Nothing token-like
 * is logged (audit carries user ids + outcomes only). All refresh failures answer
 * with the same generic message so callers cannot distinguish missing / expired /
 * revoked / reused / foreign tokens.</p>
 *
 * <p>Concurrency: rotation reads the row with a pessimistic write lock
 * (see {@code RefreshTokenRepository}), so concurrent uses serialize in the
 * database — the loser observes the winner's {@code used_at} mark and takes the
 * reuse path. Two valid replacements from one token are impossible; the
 * {@code UNIQUE} hash constraint is the backstop.</p>
 */
@Service
@ConditionalOnProperty(prefix = "finagent.auth", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class RefreshTokenService {

    /** Refresh-token entropy: 256 bits from a CSPRNG, Base64URL-encoded. */
    static final int TOKEN_BYTES = 32;

    /** Single generic failure message for every refresh rejection. */
    static final String GENERIC_FAILURE = "Invalid or expired refresh token";

    private final RefreshTokenRepository tokens;
    private final long refreshTtlMillis;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, FinAgentProperties properties,
                               AuditService audit) {
        this.tokens = tokens;
        this.refreshTtlMillis = properties.getAuth().getRefreshTokenTtl().toMillis();
        this.audit = audit;
    }

    /** Seconds until a newly issued refresh token expires (reported to clients). */
    public long refreshExpiresInSeconds() {
        return refreshTtlMillis / 1000;
    }

    /**
     * Mint the first token of a new family (login / register). Returns the RAW
     * token — the only time it ever leaves the server outside this response.
     */
    @Transactional
    public IssuedToken issue(User user) {
        String raw = generateRawToken();
        RefreshToken row = new RefreshToken(user, sha256Hex(raw), UUID.randomUUID(),
                Instant.now().plusMillis(refreshTtlMillis));
        tokens.save(row);
        return new IssuedToken(raw, refreshExpiresInSeconds());
    }

    /**
     * Consume one refresh token and mint its replacement. Exactly-once: the
     * presented row is marked used in the same transaction that inserts the
     * successor. Reuse (already-used row) revokes the whole family.
     *
     * @throws InvalidTokenException with a generic message for every rejection
     */
    // No rollback on business rejections: the reuse path MUST commit its family
    // revocation before throwing (unexpected errors still roll back).
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public Rotation rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException(GENERIC_FAILURE);
        }
        Instant now = Instant.now();
        RefreshToken presented = tokens.findByTokenHashForUpdate(sha256Hex(rawToken.trim()))
                .orElseThrow(() -> {
                    audit.record(AuditEventType.AUTH_REFRESH, null, AuditEvent.Result.FAILURE,
                            AuditService.valueMetadata("reason", "unknown-token"));
                    return new InvalidTokenException(GENERIC_FAILURE);
                });
        User user = presented.getUser();
        if (!presented.getExpiresAt().isAfter(now)) {
            log.warn("Refresh rejected: token expired");
            audit.record(AuditEventType.AUTH_REFRESH, user.getId(), AuditEvent.Result.FAILURE,
                    AuditService.valueMetadata("reason", "expired"));
            throw new InvalidTokenException(GENERIC_FAILURE);
        }
        if (presented.getRevokedAt() != null) {
            log.warn("Refresh rejected: token revoked");
            audit.record(AuditEventType.AUTH_REFRESH, user.getId(), AuditEvent.Result.FAILURE,
                    AuditService.valueMetadata("reason", "revoked"));
            throw new InvalidTokenException(GENERIC_FAILURE);
        }
        if (presented.getUsedAt() != null) {
            revokeFamily(presented.getFamilyId(), now);
            log.warn("Refresh-token reuse detected: family revoked");
            audit.record(AuditEventType.AUTH_REFRESH_REUSE, user.getId(), AuditEvent.Result.DENIED,
                    AuditService.valueMetadata("reason", "reuse"));
            throw new InvalidTokenException(GENERIC_FAILURE);
        }
        String replacementRaw = generateRawToken();
        presented.setUsedAt(now);
        presented.setReplacedByHash(sha256Hex(replacementRaw));
        tokens.save(presented);
        RefreshToken successor = new RefreshToken(user, sha256Hex(replacementRaw),
                presented.getFamilyId(), now.plusMillis(refreshTtlMillis));
        tokens.save(successor);
        audit.record(AuditEventType.AUTH_REFRESH, user.getId(), AuditEvent.Result.SUCCESS,
                AuditService.idMetadata("userId", user.getId()));
        return new Rotation(user, replacementRaw, refreshExpiresInSeconds());
    }

    /**
     * End the session chain that the presented token belongs to. Idempotent:
     * unknown or blank tokens succeed silently (logout must not leak token
     * validity, and double-logout is normal).
     */
    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Instant now = Instant.now();
        tokens.findByTokenHash(sha256Hex(rawToken.trim()))
                .ifPresent(presented -> {
                    revokeFamily(presented.getFamilyId(), now);
                    log.info("User logged out: session family revoked");
                    audit.record(AuditEventType.AUTH_LOGOUT, presented.getUser().getId(),
                            AuditEvent.Result.SUCCESS,
                            AuditService.idMetadata("userId", presented.getUser().getId()));
                });
    }

    /** Mark every non-revoked row of the family revoked as of {@code now}. */
    private void revokeFamily(UUID familyId, Instant now) {
        List<RefreshToken> family = tokens.findByFamilyId(familyId);
        for (RefreshToken row : family) {
            if (row.getRevokedAt() == null) {
                row.setRevokedAt(now);
                tokens.save(row);
            }
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of the raw token. Visible for the hash-storage tests. */
    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory in every Java SE implementation.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Raw refresh token + lifetime. The raw value is never persisted. */
    public record IssuedToken(String refreshToken, long refreshExpiresIn) {
    }

    /** Successful rotation: owner, replacement raw token, its lifetime. */
    public record Rotation(User user, String refreshToken, long refreshExpiresIn) {
    }
}
