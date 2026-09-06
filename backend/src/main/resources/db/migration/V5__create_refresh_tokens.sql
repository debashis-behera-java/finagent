-- ============================================================
-- FINAGENT - Task 2: database-backed refresh-token rotation
-- One row per issued refresh token. Only the SHA-256 hash is stored —
-- raw tokens never touch PostgreSQL. Rotation marks the presented row
-- used (replaced_by_hash points at its successor); all rows minted from
-- one login share a family_id so reuse of any superseded token revokes
-- the whole family. Additive only: V1-V4 objects are untouched.
-- Deleting a user cascades: sessions must not outlive the account.
-- ============================================================

CREATE TABLE refresh_tokens (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash        VARCHAR(64) NOT NULL UNIQUE,
    family_id         UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    used_at           TIMESTAMPTZ,
    replaced_by_hash  VARCHAR(64),
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT chk_refresh_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
