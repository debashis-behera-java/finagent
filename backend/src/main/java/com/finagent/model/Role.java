package com.finagent.model;

/**
 * Phase 16: application role. Registration always assigns {@link #USER};
 * {@link #ADMIN} is assigned out-of-band only (never self-registered).
 */
public enum Role {
    USER,
    ADMIN
}
