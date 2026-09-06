package com.finagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of a completed research run: executive summary, LLM interpretation (prose only),
 * deterministic metrics snapshot (the authoritative numbers) and sentiment summary.
 */
@Entity
@Table(name = "research_results", indexes = {
        @Index(name = "idx_research_results_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class ResearchResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, unique = true)
    private ResearchRequest request;

    @Column(columnDefinition = "text")
    private String executiveSummary;

    /** LLM-generated interpretation prose. Never contains authoritative numbers. */
    @Column(columnDefinition = "text")
    private String interpretation;

    /** JSON snapshot of the deterministic analysis engine output - the authoritative numbers. */
    @Column(name = "metrics_snapshot", columnDefinition = "text")
    private String metricsSnapshot;

    @Column(columnDefinition = "text")
    private String sentimentSummary;

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
