package com.finagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 2: database-backed refresh-token rotation record.
 *
 * <p>Stores ONLY the SHA-256 hex hash of the refresh token — the raw token is
 * returned to the client once at issuance/rotation and never persisted, never
 * logged, never returned again. {@code familyId} links every token minted from
 * one login (plus its rotations); replaying any superseded token revokes the
 * whole family. Rows are mutable by design (used/revoked transitions), unlike
 * the append-only audit trail.</p>
 */
@Entity
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user", columnList = "user_id"),
        @Index(name = "idx_refresh_tokens_family", columnList = "family_id")
})
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Owning user. Cascade: sessions must not outlive the account. The
     * database-level {@code ON DELETE CASCADE} (V5) is mirrored here so
     * Hibernate-generated schemas (H2 tests) behave identically.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;

    /** SHA-256 hex of the raw token (64 chars). Unique: one row per token. */
    @NotBlank
    @Size(min = 64, max = 64)
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** Token family: the login's initial token plus every rotation successor. */
    @NotNull
    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** Set when this token is consumed by a successful rotation. */
    @Column(name = "used_at")
    private Instant usedAt;

    /** Hash of the replacement token minted when this one was consumed. */
    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;

    /** Set on explicit revocation (logout, reuse-driven family revocation). */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public RefreshToken(User user, String tokenHash, UUID familyId, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    /** Usable iff neither consumed, revoked, nor expired. */
    public boolean isActive(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
