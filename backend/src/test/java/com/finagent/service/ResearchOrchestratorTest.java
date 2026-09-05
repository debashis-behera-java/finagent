package com.finagent.service;

import com.finagent.agent.ResearchAgent;
import com.finagent.agent.model.AgentContext;
import com.finagent.dto.response.ResearchAcceptedDto;
import com.finagent.dto.response.ResearchHistoryDto;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.exception.FactBundleIncompleteException;
import com.finagent.exception.ResourceNotFoundException;
import com.finagent.model.ResearchRequest;
import com.finagent.model.enums.ResearchStatus;
import com.finagent.repository.ResearchRequestRepository;
import com.finagent.support.AgentResultFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Phase 8 orchestrator tests on H2 (no PostgreSQL/Docker/internet/LLM):
 * submit → PENDING → RUNNING → COMPLETED/FAILED, persisted results, history
 * ordering, transition rules, and per-request isolation under concurrency.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchOrchestratorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @MockBean
    private ResearchAgent researchAgent;

    @Autowired
    private ResearchOrchestrator orchestrator;

    @Autowired
    private ResearchRequestRepository requests;

    @BeforeEach
    void cleanAndReset() {
        requests.deleteAll();
        reset(researchAgent);
    }

    @AfterEach
    void cleanAfter() {
        requests.deleteAll();
    }

    @Test
    void submitPersistsPendingJob() {
        when(researchAgent.run(any(AgentContext.class))).thenReturn(AgentResultFixtures.canned("AAPL"));

        ResearchAcceptedDto accepted = orchestrator.submitResearch("Research $AAPL", List.of("AAPL"));

        assertThat(accepted.researchId()).isNotNull();
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(accepted.createdAt()).isNotNull();
        ResearchRequest stored = requests.findById(accepted.researchId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ResearchStatus.PENDING);
        assertThat(stored.getRequestText()).isEqualTo("Research $AAPL");
    }

    @Test
    void transitionsThroughRunningToCompletedWithPersistedResult() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(researchAgent.run(any(AgentContext.class))).thenAnswer(inv -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
            return AgentResultFixtures.canned("AAPL");
        });

        ResearchAcceptedDto accepted = orchestrator.submitResearch("Research $AAPL", List.of("AAPL"));
        assertThat(awaitStatus(accepted.researchId(), "RUNNING").status()).isEqualTo("RUNNING");
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        ResearchStatusDto done = awaitTerminal(accepted.researchId());

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.tickers()).containsExactly("AAPL");
        assertThat(done.result()).isNotNull();
        assertThat(done.result().interpretation()).contains("educational research");
        assertThat(done.result().executiveSummary()).contains("AAPL");
        assertThat(done.result().metrics().path("tickers").get(0).asText()).isEqualTo("AAPL");
        assertThat(done.result().metrics().path("entries").get(0).path("price").asDouble())
                .isEqualTo(150.25);
        assertThat(done.result().newsSummary()).contains("AAPL");
        assertThat(done.result().disclaimer()).contains("not financial advice");
        assertThat(done.error()).isNull();
    }

    @Test
    void agentFailureBecomesFailedWithSafeError() {
        when(researchAgent.run(any(AgentContext.class)))
                .thenThrow(new FactBundleIncompleteException("All data sources failed for ticker(s) [FAIL]"));

        ResearchAcceptedDto accepted = orchestrator.submitResearch("Research it", List.of("FAIL"));
        ResearchStatusDto done = awaitTerminal(accepted.researchId());

        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.result()).isNull();
        assertThat(done.error().code()).isEqualTo("NO_FACTS");
        assertThat(done.error().message()).contains("FAIL");
    }

    @Test
    void invalidAgentInputBecomesFailedInvalidRequest() {
        when(researchAgent.run(any(AgentContext.class)))
                .thenThrow(new IllegalArgumentException("No tickers supplied"));

        ResearchAcceptedDto accepted = orchestrator.submitResearch("Research it", List.of("AAPL"));
        ResearchStatusDto done = awaitTerminal(accepted.researchId());

        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.error().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void unexpectedAgentFailureBecomesFailedWithoutLeakingDetails() {
        when(researchAgent.run(any(AgentContext.class)))
                .thenThrow(new RuntimeException("secret-phrase-from-stack-trace"));

        ResearchStatusDto done = awaitTerminal(
                orchestrator.submitResearch("Research it", List.of("AAPL")).researchId());

        assertThat(done.status()).isEqualTo("FAILED");
        assertThat(done.error().code()).isEqualTo("AGENT_FAILURE");
        assertThat(done.error().message()).doesNotContain("secret-phrase");
    }

    @Test
    void rejectsBlankQueryAndInvalidTicker() {
        assertThatThrownBy(() -> orchestrator.submitResearch("  ", List.of("AAPL")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> orchestrator.submitResearch("Research", List.of("BAD!SYMBOL")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownIdThrowsNotFound() {
        assertThatThrownBy(() -> orchestrator.getResearch(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void historyReturnsNewestFirstWithPagination() {
        when(researchAgent.run(any(AgentContext.class))).thenReturn(AgentResultFixtures.canned("AAPL"));
        List<UUID> ids = new ArrayList<>();
        ids.add(orchestrator.submitResearch("first", List.of("AAPL")).researchId());
        ids.add(orchestrator.submitResearch("second", List.of("AAPL")).researchId());
        ids.add(orchestrator.submitResearch("third", List.of("AAPL")).researchId());
        for (UUID id : ids) {
            awaitTerminal(id);
        }

        ResearchHistoryDto page0 = orchestrator.listHistory(PageRequest.of(0, 2));
        assertThat(page0.totalElements()).isEqualTo(3);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content().get(0).researchId()).isEqualTo(ids.get(2));
        assertThat(page0.content().get(1).researchId()).isEqualTo(ids.get(1));
        // History items carry no result/error payloads.
        assertThat(page0.content().get(0).result()).isNull();

        ResearchHistoryDto page1 = orchestrator.listHistory(PageRequest.of(1, 2));
        assertThat(page1.content()).hasSize(1);
        assertThat(page1.content().get(0).researchId()).isEqualTo(ids.get(0));
    }

    @Test
    void failingRequestDoesNotAffectAnother() {
        when(researchAgent.run(any(AgentContext.class))).thenAnswer(inv -> {
            AgentContext ctx = inv.getArgument(0);
            if (ctx.tickers().contains("FAIL")) {
                throw new FactBundleIncompleteException("All data sources failed");
            }
            return AgentResultFixtures.canned("AAPL");
        });

        UUID ok = orchestrator.submitResearch("good", List.of("AAPL")).researchId();
        UUID bad = orchestrator.submitResearch("bad", List.of("FAIL")).researchId();

        assertThat(awaitTerminal(ok).status()).isEqualTo("COMPLETED");
        assertThat(awaitTerminal(bad).status()).isEqualTo("FAILED");
    }

    @Test
    void rejectsInvalidStatusTransitions() {
        ResearchRequest pending = new ResearchRequest();
        pending.setStatus(ResearchStatus.PENDING);
        assertThatThrownBy(() -> orchestrator.transitionTo(pending, ResearchStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> orchestrator.transitionTo(pending, ResearchStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);
        orchestrator.transitionTo(pending, ResearchStatus.RUNNING);

        assertThatThrownBy(() -> orchestrator.transitionTo(pending, ResearchStatus.PENDING))
                .isInstanceOf(IllegalStateException.class);
        orchestrator.transitionTo(pending, ResearchStatus.COMPLETED);

        assertThatThrownBy(() -> orchestrator.transitionTo(pending, ResearchStatus.RUNNING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> orchestrator.transitionTo(pending, ResearchStatus.FAILED))
                .isInstanceOf(IllegalStateException.class);

        ResearchRequest failed = new ResearchRequest();
        failed.setStatus(ResearchStatus.FAILED);
        assertThatThrownBy(() -> orchestrator.transitionTo(failed, ResearchStatus.COMPLETED))
                .isInstanceOf(IllegalStateException.class);

        ResearchRequest running = new ResearchRequest();
        running.setStatus(ResearchStatus.RUNNING);
        orchestrator.transitionTo(running, ResearchStatus.FAILED);
        assertThat(running.getStatus()).isEqualTo(ResearchStatus.FAILED);
    }

    private ResearchStatusDto awaitStatus(UUID id, String status) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ResearchStatusDto dto = orchestrator.getResearch(id);
            if (dto.status().equals(status)) {
                return dto;
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for status " + status + " on " + id);
    }

    private ResearchStatusDto awaitTerminal(UUID id) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ResearchStatusDto dto = orchestrator.getResearch(id);
            if (dto.status().equals("COMPLETED") || dto.status().equals("FAILED")) {
                return dto;
            }
            sleep();
        }
        throw new AssertionError("Timed out waiting for terminal status on " + id);
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
