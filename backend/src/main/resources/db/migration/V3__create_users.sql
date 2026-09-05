-- ============================================================
-- FINAGENT - Phase 16: authentication principals
-- Minimal user table for the auth boundary. Additive only:
-- V1/V2 objects are untouched.
-- Email is stored normalized (lower-cased by the application);
-- the UNIQUE constraint therefore enforces case-insensitive
-- uniqueness. Only BCrypt hashes are stored (never plaintext).
-- ============================================================

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(60)  NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX idx_users_email ON users (email);
