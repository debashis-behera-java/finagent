package com.finagent.model;

import com.finagent.model.enums.ResearchStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One research run: user request text, optional portfolio, lifecycle status.
 * Owns the 1:1 result, the N:1 tool-execution audit trail and the 1:1 report metadata.
 */
@Entity
@Table(name = "research_requests", indexes = {
        @Index(name = "idx_research_requests_status", columnList = "status"),
        @Index(name = "idx_research_requests_created_at", columnList = "created_at"),
        @Index(name = "idx_research_requests_portfolio_id", columnList = "portfolio_id")
})
@Getter
@Setter
@NoArgsConstructor
public class ResearchRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String requestText;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "research_request_tickers",
            joinColumns = @JoinColumn(name = "research_request_id"))
    @Column(name = "ticker", length = 12, nullable = false)
    private Set<@NotBlank @Size(max = 12) String> tickers = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id")
    private Portfolio portfolio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResearchStatus status = ResearchStatus.PENDING;

    /** When execution started (null while PENDING). Set once on PENDING → RUNNING. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When execution finished (null until COMPLETED/FAILED). Set once, terminal. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Machine-readable failure reason, set only on FAILED (e.g. NO_FACTS,
     * SYNTHESIS_FAILED, AGENT_FAILURE). Never contains secrets or stack traces.
     */
    @Size(max = 50)
    @Column(name = "error_code", length = 50)
    private String errorCode;

    /** Safe human-readable failure message, set only on FAILED. No stack traces. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @OneToOne(mappedBy = "request", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private ResearchResult result;

    @OneToMany(mappedBy = "researchRequest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ResearchToolExecution> toolExecutions = new ArrayList<>();

    @OneToOne(mappedBy = "researchRequest", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    private ReportMetadata reportMetadata;

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

    public void addToolExecution(ResearchToolExecution execution) {
        this.toolExecutions.add(execution);
        execution.setResearchRequest(this);
    }
}
