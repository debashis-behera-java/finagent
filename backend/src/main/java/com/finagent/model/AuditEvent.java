package com.finagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 17: immutable security audit entry. Append-only by convention — the
 * application never updates or deletes rows (no setters at all).
 *
 * <p>Stores the minimum: event type, timestamp, safe user reference, outcome,
 * and small safe metadata (ids, never secrets: no passwords, tokens, keys,
 * or request bodies — enforced by the call sites in {@code AuditService}).</p>
 */
@Entity
@Table(name = "audit_events", indexes = {
        @Index(name = "idx_audit_events_type_time", columnList = "event_type,occurred_at")
})
@Getter
@NoArgsConstructor
public class AuditEvent {

    /** Outcomes. DENIED = authorization refusal (403-class events). */
    public enum Result {
        SUCCESS,
        FAILURE,
        DENIED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private AuditEventType eventType;

    /** Safe user reference; null for anonymous/failed-identity events. */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Result result;

    /** Tiny safe JSON (ids, hashed identifiers). Max length enforced by AuditService. */
    @NotBlank
    @Size(max = 2000)
    @Column(columnDefinition = "text", updatable = false)
    private String metadata = "{}";

    public AuditEvent(AuditEventType eventType, UUID userId, Result result, String metadata) {
        this.eventType = eventType;
        this.userId = userId;
        this.result = result;
        this.metadata = metadata == null || metadata.isBlank() ? "{}" : metadata;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = Instant.now();
    }
}
