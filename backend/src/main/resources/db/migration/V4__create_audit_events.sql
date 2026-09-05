-- ============================================================
-- FINAGENT - Phase 17: immutable security audit trail
-- Append-only (no updates/deletes by the application): who did what, when,
-- with what outcome. Additive only: V1-V3 objects are untouched.
-- user_id is intentionally NOT a foreign key: audit rows must survive
-- account deletion and must never cascade. No secrets are stored here —
-- passwords, tokens, keys and request bodies are never recorded.
-- ============================================================

CREATE TABLE audit_events (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    event_type  VARCHAR(50) NOT NULL,
    user_id     UUID,
    result      VARCHAR(20) NOT NULL CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    metadata    TEXT
);

CREATE INDEX idx_audit_events_type_time ON audit_events (event_type, occurred_at);
