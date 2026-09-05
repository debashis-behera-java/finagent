package com.finagent.service;

import com.finagent.agent.ResearchAgent;
import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.config.AsyncConfig;
import com.finagent.exception.AgentException;
import com.finagent.exception.FactBundleIncompleteException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Background execution of one research job (Phase 8).
 *
 * <p>Runs on the bounded {@code researchExecutor} pool. Holds no transaction across
 * the agent call — every state change goes through short {@link ResearchOrchestrator}
 * transactions. Every throwable is captured and mapped to a FAILED row, so a job can
 * never get stuck in RUNNING and one failing job never affects another.</p>
 */
@Component
@ConditionalOnProperty(prefix = "finagent.research", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class AsyncResearchRunner {

    private final ResearchOrchestrator orchestrator;
    private final ResearchAgent agent;

    // @Lazy breaks the orchestrator <-> runner constructor cycle: the orchestrator
    // dispatches to the runner, the runner calls back for state transitions.
    public AsyncResearchRunner(@Lazy ResearchOrchestrator orchestrator, ResearchAgent agent) {
        this.orchestrator = orchestrator;
        this.agent = agent;
    }

    @Async(AsyncConfig.RESEARCH_EXECUTOR)
    public void run(UUID researchId) {
        AgentContext context;
        try {
            context = orchestrator.beginRun(researchId);
        } catch (RuntimeException ex) {
            // Job vanished or already terminal — nothing to execute.
            log.warn("Research {} cannot start: {}", researchId, ex.getMessage());
            return;
        }
        try {
            AgentResult result = agent.run(context);
            orchestrator.completeRun(researchId, result);
        } catch (FactBundleIncompleteException ex) {
            orchestrator.failRun(researchId, "NO_FACTS", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            orchestrator.failRun(researchId, "INVALID_REQUEST", ex.getMessage());
        } catch (AgentException ex) {
            orchestrator.failRun(researchId, "SYNTHESIS_FAILED", ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Research {} failed unexpectedly: {}", researchId, ex.getMessage(), ex);
            orchestrator.failRun(researchId, "AGENT_FAILURE", "Research execution failed unexpectedly");
        }
    }
}
