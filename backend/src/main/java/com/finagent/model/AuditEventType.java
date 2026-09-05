package com.finagent.model;

/**
 * Phase 17: security-sensitive application events. Closed set — adding an event
 * means a conscious decision, not a free-form string.
 */
public enum AuditEventType {
    AUTH_REGISTER,
    AUTH_LOGIN_SUCCESS,
    AUTH_LOGIN_FAILURE,
    RESEARCH_SUBMITTED,
    RESEARCH_COMPLETED,
    RESEARCH_FAILED,
    ADMIN_USERS_LISTED
}
