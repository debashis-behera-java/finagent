package com.finagent.controller;

import com.finagent.agent.ResearchAgent;
import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.model.GuardResult;
import com.finagent.market.dto.Quote;
import com.finagent.model.ResearchRequest;
import com.finagent.model.enums.ResearchStatus;
import com.finagent.repository.ResearchRequestRepository;
import com.finagent.support.AgentResultFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10 endpoint tests (MockMvc, H2, mocked agent): 200 PDF download shape,
 * 404/409 gating, multi-stock and multi-page reports, all parse-back verified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class ResearchReportControllerTest {

    @MockBean
    private ResearchAgent researchAgent;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResearchRequestRepository requests;

    @Autowired
    private com.finagent.service.ResearchOrchestrator orchestrator;

    @BeforeEach
    void cleanAndReset() {
        requests.deleteAll();
        reset(researchAgent);
        when(researchAgent.run(any(AgentContext.class)))
                .thenAnswer(inv -> AgentResultFixtures.canned("AAPL"));
    }

    @AfterEach
    void cleanAfter() {
        requests.deleteAll();
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pagesOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getPages().getCount();
        }
    }

    private UUID persistWithStatus(ResearchStatus status) {
        ResearchRequest request = new ResearchRequest();
        request.setRequestText("Direct fixture");
        request.setTickers(new LinkedHashSet<>(List.of("AAPL")));
        request.setStatus(status);
        if (status == ResearchStatus.RUNNING) {
            request.setStartedAt(Instant.now());
        }
        if (status == ResearchStatus.FAILED) {
            request.setStartedAt(Instant.now());
            request.setCompletedAt(Instant.now());
            request.setErrorCode("AGENT_FAILURE");
            request.setErrorMessage("boom");
        }
        return requests.saveAndFlush(request).getId();
    }

    private UUID submitAndAwait(String tickersJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Report me.\",\"tickers\":" + tickersJson + "}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        UUID id = UUID.fromString(body.replaceAll(".*\"researchId\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            String current = orchestrator.getResearch(id).status();
            if (current.equals("COMPLETED") || current.equals("FAILED")) {
                return id;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for terminal status on " + id);
    }

    @Test
    void completedReportDownloadsAsPdf() throws Exception {
        UUID id = submitAndAwait("[\"AAPL\"]");

        MvcResult result = mockMvc.perform(get("/api/v1/research/{id}/report.pdf", id))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"finagent-research-" + id + ".pdf\""))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        String text = textOf(pdf);
        assertThat(text).contains("FINAGENT").contains("AAPL").contains("150.25")
                .contains("DISCLAIMER").contains(id.toString());
    }

    @Test
    void unknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/research/{id}/report.pdf", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void pendingRunningAndFailedReturn409WithoutPdf() throws Exception {
        for (ResearchStatus state : List.of(ResearchStatus.PENDING, ResearchStatus.RUNNING, ResearchStatus.FAILED)) {
            UUID id = persistWithStatus(state);
            mockMvc.perform(get("/api/v1/research/{id}/report.pdf", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(state.name())));
        }
    }

    @Test
    void multiStockReportCoversEveryTicker() throws Exception {
        reset(researchAgent);
        when(researchAgent.run(any(AgentContext.class)))
                .thenAnswer(inv -> AgentResultFixtures.canned("TCS", "INFY", "RELIANCE"));
        UUID id = submitAndAwait("[\"TCS\",\"INFY\",\"RELIANCE\"]");

        byte[] pdf = mockMvc.perform(get("/api/v1/research/{id}/report.pdf", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        String text = textOf(pdf);
        assertThat(text).contains("TCS").contains("INFY").contains("RELIANCE");
    }

    @Test
    void longInterpretationSpansMultiplePages() throws Exception {
        reset(researchAgent);
        StringBuilder interpretation = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            interpretation.append("Finding ").append(i)
                    .append(": the outlook remains steady with balanced risks. ");
        }
        when(researchAgent.run(any(AgentContext.class))).thenAnswer(inv -> {
            Quote quote = new Quote("AAPL", new BigDecimal("150.25"),
                    BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
            FactEntry entry = new FactEntry("AAPL", quote, null, 5, null, null, 0,
                    List.of(), null, null, List.of());
            FactBundle bundle = new FactBundle("Report me.", List.of("AAPL"), Instant.now(), List.of(entry));
            String prose = interpretation.toString();
            return new AgentResult(bundle, prose, new GuardResult(true, List.of(), prose), Instant.now());
        });
        UUID id = submitAndAwait("[\"AAPL\"]");

        byte[] pdf = mockMvc.perform(get("/api/v1/research/{id}/report.pdf", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pagesOf(pdf)).isGreaterThan(1);
        // Normalize whitespace: long lines wrap mid-phrase ("Finding\n149:").
        String text = textOf(pdf).replaceAll("\\s+", " ");
        assertThat(text).contains("Finding 0").contains("Finding 149");
    }
}
