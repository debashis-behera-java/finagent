package com.finagent.repository;

import com.finagent.model.Portfolio;
import com.finagent.model.PortfolioHolding;
import com.finagent.model.ResearchRequest;
import com.finagent.model.ResearchResult;
import com.finagent.model.enums.ResearchStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-PostgreSQL integration tests (Phase 13).
 *
 * <p>Runs the full application context against an ephemeral PostgreSQL 16
 * Testcontainer: Flyway migrates V1+V2, Hibernate <b>validates</b> (never
 * creates), and the actual repositories persist the research lifecycle.
 * Requires a Docker/container runtime; excluded from the default
 * {@code mvn verify} (failsafe {@code *IT} naming) — run with:
 * {@code mvn verify -Ppostgres-integration}.</p>
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class PostgresResearchIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("finagent");

    @Autowired
    private ResearchRequestRepository requestRepository;

    @Autowired
    private ResearchResultRepository resultRepository;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    // ---------- infrastructure ----------

    @Test
    void applicationConnectsToRealPostgres() {
        assertThat(jdbc.queryForObject("select version()", String.class)).contains("PostgreSQL 16");
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("finagent");
    }

    @Test
    void flywayAppliedAllMigrations() {
        List<String> applied = Arrays.stream(flyway.info().applied())
                .map(MigrationInfo::getVersion)
                .map(Object::toString)
                .toList();

        assertThat(applied).containsExactly("1", "2", "3", "4");
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Long.class))
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    void migratedTablesExist() {
        for (String table : List.of("portfolios", "portfolio_holdings", "research_requests",
                "research_request_tickers", "research_results", "research_tool_executions",
                "report_metadata")) {
            assertThat(jdbc.queryForObject(
                    "select count(*) from " + table, Long.class)).isZero();
        }
    }

    // ---------- research lifecycle ----------

    private ResearchRequest newRequest(String text, String... tickers) {
        ResearchRequest request = new ResearchRequest();
        request.setRequestText(text);
        request.getTickers().addAll(List.of(tickers));
        request.setStatus(ResearchStatus.PENDING);
        return requestRepository.saveAndFlush(request);
    }

    @Test
    void researchLifecyclePersistsAndRetrieves() {
        ResearchRequest saved = newRequest("Analyze AAPL for a moderate-risk investor", "AAPL", "MSFT");
        UUID id = saved.getId();
        assertThat(id).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        em.clear();
        ResearchRequest loaded = requestRepository.findById(id).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ResearchStatus.PENDING);
        assertThat(loaded.getRequestText()).contains("moderate-risk");
        assertThat(loaded.getTickers()).containsExactlyInAnyOrder("AAPL", "MSFT");
        assertThat(loaded.getStartedAt()).isNull();

        loaded.setStatus(ResearchStatus.RUNNING);
        loaded.setStartedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        requestRepository.saveAndFlush(loaded);
        em.clear();

        ResearchRequest running = requestRepository.findById(id).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(ResearchStatus.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();

        running.setStatus(ResearchStatus.COMPLETED);
        running.setCompletedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        ResearchResult result = new ResearchResult();
        result.setRequest(running);
        running.setResult(result);
        result.setExecutiveSummary("AAPL looks steady — educational research only.");
        result.setInterpretation("Coverage reads constructive. Not financial advice. Café ☕ naïve façade …");
        result.setMetricsSnapshot("{\"tickers\":[\"AAPL\"],\"entries\":[{\"symbol\":\"AAPL\",\"price\":150.25,"
                + "\"sentiment\":{\"label\":\"POSITIVE\",\"score\":0.6}}],\"disclaimer\":\"study\"}"
                .repeat(40));
        result.setSentimentSummary("AAPL (2 headlines, latest: beats earnings)");
        requestRepository.saveAndFlush(running);
        em.clear();

        ResearchRequest done = requestRepository.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ResearchStatus.COMPLETED);
        assertThat(done.getCompletedAt()).isNotNull();
        ResearchResult stored = resultRepository.findByRequestId(id).orElseThrow();
        assertThat(stored.getExecutiveSummary()).startsWith("AAPL looks steady");
        assertThat(stored.getInterpretation()).contains("Café");
        assertThat(stored.getMetricsSnapshot()).contains("\"score\":0.6");
        assertThat(stored.getMetricsSnapshot()).hasSizeGreaterThan(4000);
        assertThat(stored.getSentimentSummary()).contains("beats earnings");
    }

    @Test
    void failedLifecyclePersistsErrorInfo() {
        ResearchRequest saved = newRequest("Analyze FAIL", "FAIL");
        saved.setStatus(ResearchStatus.RUNNING);
        saved.setStartedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        saved.setStatus(ResearchStatus.FAILED);
        saved.setCompletedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        saved.setErrorCode("NO_FACTS");
        saved.setErrorMessage("All data sources failed for ticker(s) [FAIL]");
        requestRepository.saveAndFlush(saved);
        em.clear();

        ResearchRequest loaded = requestRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ResearchStatus.FAILED);
        assertThat(loaded.getErrorCode()).isEqualTo("NO_FACTS");
        assertThat(loaded.getErrorMessage()).contains("[FAIL]");
    }

    @Test
    void historyQueryReturnsNewestFirst() {
        ResearchRequest first = newRequest("first", "AAPL");
        ResearchRequest second = newRequest("second", "MSFT");
        ResearchRequest third = newRequest("third", "AAPL");

        Page<ResearchRequest> page =
                requestRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(ResearchRequest::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    @Test
    void statusFilterQueryWorks() {
        newRequest("pending one", "AAPL");
        ResearchRequest running = newRequest("running one", "MSFT");
        running.setStatus(ResearchStatus.RUNNING);
        requestRepository.saveAndFlush(running);

        Page<ResearchRequest> pending =
                requestRepository.findByStatus(ResearchStatus.PENDING, PageRequest.of(0, 10));

        assertThat(pending.getTotalElements()).isEqualTo(1);
        assertThat(pending.getContent().get(0).getRequestText()).isEqualTo("pending one");
    }

    // ---------- Postgres-specific validation ----------

    @Test
    void enumsAreStoredAsStrings() {
        ResearchRequest saved = newRequest("enum check", "AAPL");

        Object raw = em.createNativeQuery("select status from research_requests where id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();

        assertThat(raw).isEqualTo("PENDING");
    }

    @Test
    void secondResultForSameRequestViolatesUniqueConstraint() {
        ResearchRequest saved = newRequest("unique check", "AAPL");
        ResearchResult first = new ResearchResult();
        first.setRequest(saved);
        saved.setResult(first);
        requestRepository.saveAndFlush(saved);

        ResearchResult second = new ResearchResult();
        second.setRequest(saved);

        assertThatThrownBy(() -> {
            resultRepository.saveAndFlush(second);
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void numericPrecisionSurvivesRoundTrip() {
        Portfolio portfolio = new Portfolio();
        portfolio.setName("Precision-" + UUID.randomUUID());
        PortfolioHolding holding = new PortfolioHolding();
        holding.setTicker("AAPL");
        holding.setQuantity(new BigDecimal("10.1234"));
        holding.setCostBasis(new BigDecimal("150.5678"));
        holding.setSector("Technology");
        portfolio.addHolding(holding);
        portfolioRepository.saveAndFlush(portfolio);
        em.clear();

        PortfolioHolding loaded = portfolioRepository.findWithHoldingsById(portfolio.getId())
                .orElseThrow().getHoldings().get(0);

        assertThat(loaded.getQuantity()).isEqualByComparingTo(new BigDecimal("10.1234"));
        assertThat(loaded.getCostBasis()).isEqualByComparingTo(new BigDecimal("150.5678"));
    }

    @Test
    void instantTimestampsSurviveRoundTrip() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ResearchRequest saved = newRequest("time check", "AAPL");
        saved.setStartedAt(started);
        requestRepository.saveAndFlush(saved);
        em.clear();

        assertThat(requestRepository.findById(saved.getId()).orElseThrow().getStartedAt())
                .isEqualTo(started);
    }
}
