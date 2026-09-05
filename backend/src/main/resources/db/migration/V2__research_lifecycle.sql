-- ============================================================
-- FINAGENT - Phase 8: research lifecycle columns
-- Adds run timestamps and safe failure info to research_requests.
-- Nullable: Phase 1-7 rows (and H2 create-drop tests) are unaffected.
-- ============================================================

ALTER TABLE research_requests
    ADD COLUMN started_at   TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN error_code   VARCHAR(50),
    ADD COLUMN error_message TEXT;
