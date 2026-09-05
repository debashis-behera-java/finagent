package com.finagent.repository;

import com.finagent.model.Portfolio;
import com.finagent.model.PortfolioHolding;
import com.finagent.model.ReportMetadata;
import com.finagent.model.ResearchRequest;
import com.finagent.model.ResearchResult;
import com.finagent.model.ResearchToolExecution;
import com.finagent.model.enums.ResearchStatus;
import com.finagent.model.enums.ToolExecutionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository integration tests (Phase 2).
 *
 * <p>Runs on in-memory H2 because this machine has no Docker/PostgreSQL;
 * Phase 13 replaces this with Testcontainers against real PostgreSQL and
 * the Flyway migration.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
class JpaRepositoriesIntegrationTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ResearchRequestRepository requestRepository;

    @Autowired
    private ResearchResultRepository resultRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private PortfolioHoldingRepository holdingRepository;

    @Autowired
    private ResearchToolExecutionRepository toolExecutionRepository;

    @Autowired
    private ReportMetadataRepository reportMetadataRepository;

    // ---------- helpers ----------

    private Portfolio portfolio(String name) {
        Portfolio p = new Portfolio();
        p.setName(name);
        return p;
    }

    private PortfolioHolding holding(String ticker, String qty, String cost, String sector) {
        PortfolioHolding h = new PortfolioHolding();
        h.setTicker(ticker);
        h.setQuantity(new BigDecimal(qty));
        h.setCostBasis(new BigDecimal(cost));
        h.setSector(sector);
        return h;
    }

    /** Builds and persists the full research-request aggregate graph. */
    private ResearchRequest persistedRequest() {
        Portfolio p = portfolio("Core-" + UUID.randomUUID());
        p.addHolding(holding("AAPL", "10", "150.50", "Technology"));
        em.persistAndFlush(p);

        ResearchRequest r = new ResearchRequest();
        r.setRequestText("Analyze AAPL and MSFT risk");
        r.getTickers().add("AAPL");
        r.getTickers().add("MSFT");
        r.setPortfolio(p);

        ResearchResult result = new ResearchResult();
        result.setRequest(r);
        r.setResult(result);

        ResearchToolExecution t1 = new ResearchToolExecution();
        t1.setToolName("get_stock_price");
        t1.setStatus(ToolExecutionStatus.SUCCESS);
        t1.setDurationMs(120L);
        t1.setExecutedAt(Instant.now());
        r.addToolExecution(t1);

        ResearchToolExecution t2 = new ResearchToolExecution();
        t2.setToolName("search_financial_news");
        t2.setStatus(ToolExecutionStatus.FAILED);
        t2.setErrorMessage("provider timeout");
        t2.setExecutedAt(Instant.now());
        r.addToolExecution(t2);

        ReportMetadata meta = new ReportMetadata();
        meta.setResearchRequest(r);
        meta.setFilePath("reports/" + UUID.randomUUID() + ".pdf");
        meta.setPageCount(6);
        meta.setGeneratedAt(Instant.now());
        r.setReportMetadata(meta);

        return requestRepository.saveAndFlush(r);
    }

    // ---------- tests ----------

    @Test
    void portfolioRoundTripWithHoldings() {
        Portfolio p = portfolio("Growth-" + UUID.randomUUID());
        p.addHolding(holding("AAPL", "5", "100.00", "Technology"));
        p.addHolding(holding("JNJ", "3", "160.00", "Healthcare"));
        p = portfolioRepository.saveAndFlush(p);
        em.clear();

        Portfolio loaded = portfolioRepository.findWithHoldingsById(p.getId()).orElseThrow();
        assertThat(loaded.getHoldings()).hasSize(2);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(holdingRepository.findByPortfolioId(p.getId())).hasSize(2);
        assertThat(portfolioRepository.findByName(loaded.getName())).isPresent();
    }

    @Test
    void researchRequestCascadesToResultExecutionsAndMetadata() {
        ResearchRequest saved = persistedRequest();
        UUID id = saved.getId();
        em.clear();

        ResearchRequest loaded = requestRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ResearchStatus.PENDING);
        assertThat(loaded.getRequestText()).isEqualTo("Analyze AAPL and MSFT risk");
        assertThat(loaded.getPortfolio()).isNotNull();

        assertThat(resultRepository.findByRequestId(id)).isPresent();
        assertThat(toolExecutionRepository.findByResearchRequestIdOrderByExecutedAtAsc(id)).hasSize(2);
        assertThat(toolExecutionRepository
                .countByResearchRequestIdAndStatus(id, ToolExecutionStatus.SUCCESS)).isEqualTo(1);
        assertThat(reportMetadataRepository.findByResearchRequestId(id)).isPresent();
    }

    @Test
    void enumsAreStoredAsStrings() {
        ResearchRequest saved = persistedRequest();

        Object raw = em.getEntityManager()
                .createNativeQuery("select status from research_requests where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(raw).isEqualTo("PENDING");
    }

    @Test
    void orphanRemovalDeletesRemovedToolExecution() {
        ResearchRequest saved = persistedRequest();
        UUID id = saved.getId();
        em.clear();

        ResearchRequest managed = requestRepository.findById(id).orElseThrow();
        managed.getToolExecutions().remove(0);
        requestRepository.saveAndFlush(managed);

        assertThat(toolExecutionRepository.findByResearchRequestIdOrderByExecutedAtAsc(id)).hasSize(1);
    }

    @Test
    void deletingRequestCascadesToChildren() {
        ResearchRequest saved = persistedRequest();
        UUID id = saved.getId();

        requestRepository.delete(saved);
        requestRepository.flush();

        assertThat(requestRepository.findById(id)).isEmpty();
        assertThat(resultRepository.findByRequestId(id)).isEmpty();
        assertThat(toolExecutionRepository.findByResearchRequestIdOrderByExecutedAtAsc(id)).isEmpty();
        assertThat(reportMetadataRepository.findByResearchRequestId(id)).isEmpty();
    }

    @Test
    void updatedAtChangesOnModification() throws Exception {
        ResearchRequest saved = persistedRequest();
        Instant createdAt = saved.getCreatedAt();
        Instant updatedAtBefore = saved.getUpdatedAt();

        Thread.sleep(10);
        saved.setStatus(ResearchStatus.RUNNING);
        requestRepository.saveAndFlush(saved);

        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.getUpdatedAt()).isAfter(updatedAtBefore);
    }

    @Test
    void statusPagingQueryWorks() {
        persistedRequest();
        ResearchRequest running = persistedRequest();
        running.setStatus(ResearchStatus.RUNNING);
        requestRepository.saveAndFlush(running);

        Page<ResearchRequest> pendingPage =
                requestRepository.findByStatus(ResearchStatus.PENDING, PageRequest.of(0, 10));
        assertThat(pendingPage.getTotalElements()).isEqualTo(1);
        assertThat(pendingPage.getContent().get(0).getStatus()).isEqualTo(ResearchStatus.PENDING);

        assertThat(requestRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }
}


