package com.finagent.model;

import com.finagent.model.enums.ToolExecutionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * MCP observability record: one row per tool execution within a research run
 * (tool name, input hash, status, duration, error). Inputs/outputs are redacted of keys.
 */
@Entity
@Table(name = "research_tool_executions", indexes = {
        @Index(name = "idx_tool_executions_request_id", columnList = "request_id"),
        @Index(name = "idx_tool_executions_executed_at", columnList = "executed_at"),
        @Index(name = "idx_tool_executions_request_tool", columnList = "request_id,tool_name")
})
@Getter
@Setter
@NoArgsConstructor
public class ResearchToolExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private ResearchRequest researchRequest;

    @NotBlank
    @Size(max = 100)
    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    /** SHA-256 hash of the (redacted) tool input for audit purposes. */
    @Size(max = 64)
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ToolExecutionStatus status;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
