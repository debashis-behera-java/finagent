package com.finagent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.common.TickerNormalizer;
import com.finagent.dto.response.ResearchAcceptedDto;
import com.finagent.dto.response.ResearchErrorDto;
import com.finagent.dto.response.ResearchHistoryDto;
import com.finagent.dto.response.ResearchResultDto;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.exception.ResourceNotFoundException;
import com.finagent.model.AuditEvent;
import com.finagent.model.AuditEventType;
import com.finagent.model.ResearchRequest;
import com.finagent.model.ResearchResult;
import com.finagent.model.enums.ResearchStatus;
import com.finagent.repository.ResearchRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Research job lifecycle owner (Phase 8): persisted PENDING → RUNNING →
 * COMPLETED / FAILED workflow around the Phase 7 {@code ResearchAgent}.
 *
 * <p>Transaction discipline: every state change runs in its own short transaction
 * ({@link AsyncResearchRunner} never holds a transaction across the agent call).
 * Lazy associations are therefore mapped to DTOs inside read-only transactions
 * here — controllers stay thin and never touch entities.</p>
 *
 * <p>Disabled without a database: set {@code finagent.research.enabled=false} (the
 * {@code dev} profile default) and these beans — and the REST endpoints — stay
 * out of the context. Same tradeoff as Phase 1.</p>
 */
@Service
@ConditionalOnProperty(prefix = "finagent.research", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class ResearchOrchestrator {

    static final int MAX_ERROR_MESSAGE = 2000;

    /** Allowed lifecycle transitions. Terminal states have no outgoing edges (no retry in Phase 8). */
    private static final Map<ResearchStatus, Set<ResearchStatus>> ALLOWED = new EnumMap<>(ResearchStatus.class);

    static {
        ALLOWED.put(ResearchStatus.PENDING, Set.of(ResearchStatus.RUNNING));
        ALLOWED.put(ResearchStatus.RUNNING, Set.of(ResearchStatus.COMPLETED, ResearchStatus.FAILED));
        ALLOWED.put(ResearchStatus.COMPLETED, Set.of());
        ALLOWED.put(ResearchStatus.FAILED, Set.of());
    }

    private final ResearchRequestRepository requests;
    private final AsyncResearchRunner runner;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public ResearchOrchestrator(ResearchRequestRepository requests,
                                AsyncResearchRunner runner,
                                ObjectMapper objectMapper,
                                AuditService audit) {
        this.requests = requests;
        this.runner = runner;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    /**
     * Persist a PENDING job and dispatch it exactly once to the background executor.
     * Returns immediately — the API layer maps this to HTTP 202.
     */
    @Transactional
    public ResearchAcceptedDto submitResearch(String query, List<String> tickers) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        List<String> normalized = normalizeTickers(tickers);
        ResearchRequest request = new ResearchRequest();
        request.setRequestText(query.strip());
        request.setTickers(new java.util.LinkedHashSet<>(normalized));
        request.setStatus(ResearchStatus.PENDING);
        ResearchRequest saved = requests.saveAndFlush(request);
        log.info("Research {} submitted: {} ticker(s) {}", saved.getId(), normalized.size(), normalized);
        // Dispatch exactly once, AFTER the PENDING row commits — the runner thread
        // must be able to see the row, otherwise the job would silently never start.
        UUID id = saved.getId();
        audit.record(AuditEventType.RESEARCH_SUBMITTED, currentUserId(),
                AuditEvent.Result.SUCCESS, AuditService.idMetadata("researchId", id));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runner.run(id);
                }
            });
        } else {
            runner.run(id);
        }
        return new ResearchAcceptedDto(saved.getId(), saved.getStatus().name(), saved.getCreatedAt());
    }

    /** PENDING → RUNNING with the execution clock started. Returns the agent input. */
    @Transactional
    public AgentContext beginRun(UUID id) {
        ResearchRequest request = load(id);
        transitionTo(request, ResearchStatus.RUNNING);
        request.setStartedAt(Instant.now());
        requests.save(request);
        log.info("Research {} PENDING -> RUNNING", id);
        return new AgentContext(request.getRequestText(),
                new ArrayList<>(request.getTickers()), null, null, null);
    }

    /** RUNNING → COMPLETED with the structured result persisted. */
    @Transactional
    public void completeRun(UUID id, AgentResult result) {
        ResearchRequest request = load(id);
        transitionTo(request, ResearchStatus.COMPLETED);
        request.setCompletedAt(Instant.now());
        request.setResult(ResearchResultAssembler.assemble(request, result, objectMapper));
        requests.save(request);
        log.info("Research {} RUNNING -> COMPLETED in {} ms", id, durationMs(request));
        audit.record(AuditEventType.RESEARCH_COMPLETED, currentUserId(),
                AuditEvent.Result.SUCCESS, AuditService.idMetadata("researchId", id));
    }

    /** RUNNING → FAILED with safe error info. Never stores secrets or stack traces. */
    @Transactional
    public void failRun(UUID id, String errorCode, String errorMessage) {
        ResearchRequest request = load(id);
        transitionTo(request, ResearchStatus.FAILED);
        request.setCompletedAt(Instant.now());
        request.setErrorCode(errorCode);
        request.setErrorMessage(truncate(errorMessage));
        requests.save(request);
        log.warn("Research {} RUNNING -> FAILED in {} ms ({}: {})",
                id, durationMs(request), errorCode, truncate(errorMessage));
        audit.record(AuditEventType.RESEARCH_FAILED, currentUserId(),
                AuditEvent.Result.FAILURE,
                AuditService.valueMetadata("errorCode", errorCode));
    }

    @Transactional(readOnly = true)
    public ResearchStatusDto getResearch(UUID id) {
        return toStatusDto(load(id), true);
    }

    @Transactional(readOnly = true)
    public ResearchHistoryDto listHistory(Pageable pageable) {
        Page<ResearchRequest> page = requests.findAllByOrderByCreatedAtDesc(pageable);
        List<ResearchStatusDto> items = page.getContent().stream()
                .map(request -> toStatusDto(request, false))
                .toList();
        return new ResearchHistoryDto(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Enforces the lifecycle state machine. Package-visible for unit tests. */
    void transitionTo(ResearchRequest request, ResearchStatus next) {
        ResearchStatus current = request.getStatus();
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(next)) {
            throw new IllegalStateException(
                    "Invalid research status transition from %s to %s".formatted(current, next));
        }
        request.setStatus(next);
    }

    private ResearchRequest load(UUID id) {
        return requests.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("Research not found: " + id));
    }

    private List<String> normalizeTickers(List<String> tickers) {
        if (tickers == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(tickers.size());
        for (String ticker : tickers) {
            normalized.add(TickerNormalizer.normalize(ticker));
        }
        return normalized;
    }

    private ResearchStatusDto toStatusDto(ResearchRequest request, boolean includePayload) {
        ResearchResultDto result = null;
        ResearchErrorDto error = null;
        if (includePayload && request.getStatus() == ResearchStatus.COMPLETED && request.getResult() != null) {
            ResearchResult stored = request.getResult();
            result = new ResearchResultDto(
                    new ArrayList<>(request.getTickers()),
                    stored.getExecutiveSummary(),
                    stored.getInterpretation(),
                    parseMetrics(stored.getMetricsSnapshot()),
                    stored.getSentimentSummary(),
                    ResearchResultAssembler.DISCLAIMER,
                    request.getCompletedAt());
        }
        if (includePayload && request.getStatus() == ResearchStatus.FAILED) {
            error = new ResearchErrorDto(request.getErrorCode(), request.getErrorMessage());
        }
        return new ResearchStatusDto(
                request.getId(),
                request.getStatus().name(),
                request.getCreatedAt(),
                request.getStartedAt(),
                request.getCompletedAt(),
                request.getRequestText(),
                new ArrayList<>(request.getTickers()),
                result,
                error);
    }

    private JsonNode parseMetrics(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(snapshot);
        } catch (JsonProcessingException ex) {
            log.warn("Stored metrics snapshot is not valid JSON, returning null node");
            return objectMapper.nullNode();
        }
    }

    private static long durationMs(ResearchRequest request) {
        if (request.getStartedAt() == null) {
            return -1;
        }
        Instant end = request.getCompletedAt() != null ? request.getCompletedAt() : Instant.now();
        return end.toEpochMilli() - request.getStartedAt().toEpochMilli();
    }

    static String truncate(String message) {
        if (message != null && message.length() > MAX_ERROR_MESSAGE) {
            return message.substring(0, MAX_ERROR_MESSAGE);
        }
        return message;
    }

    /**
     * Phase 17: audit attribution. The submit path runs on the request thread
     * (principal present); background completion/failure runs async (absent →
     * null, documented). Non-UUID principals (e.g. test mocks) map to null.
     */
    static UUID currentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
