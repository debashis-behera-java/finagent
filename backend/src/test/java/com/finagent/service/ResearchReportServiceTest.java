package com.finagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finagent.dto.response.ResearchResultDto;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.exception.ReportGenerationException;
import com.finagent.exception.ReportNotReadyException;
import com.finagent.exception.ResourceNotFoundException;
import com.finagent.report.PdfReportGenerator;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Report service tests with a mocked orchestrator (no database) and the real
 * PDFBox generator: status gating, parse-back content, and failure mapping.
 */
@ExtendWith(MockitoExtension.class)
class ResearchReportServiceTest {

    @Mock
    private ResearchOrchestrator orchestrator;

    private ResearchReportService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = new ResearchReportService(orchestrator, new PdfReportGenerator());
    }

    private ResearchStatusDto completed(UUID id) {
        ObjectNode metrics = objectMapper.createObjectNode();
        metrics.putPOJO("tickers", List.of("AAPL"));
        var entries = metrics.putArray("entries");
        ObjectNode entry = entries.addObject();
        entry.put("symbol", "AAPL");
        entry.put("companyName", "AAPL Corp.");
        entry.put("price", 150.25);
        entry.put("sector", "Technology");
        entry.put("historyBars", 21);
        entry.put("newsCount", 2);
        var headlines = entry.putArray("headlines");
        headlines.add("AAPL beats earnings expectations");
        ObjectNode risk = entry.putObject("risk");
        risk.put("score", 42.5);
        risk.put("category", "MODERATE");
        ObjectNode sentiment = entry.putObject("sentiment");
        sentiment.put("status", "available");
        sentiment.put("label", "POSITIVE");
        var guard = metrics.putObject("guard");
        guard.put("passed", true);
        guard.put("redactedCount", 0);
        ResearchResultDto result = new ResearchResultDto(
                List.of("AAPL"), "AAPL looks steady.", "Steady outlook.",
                metrics, "AAPL headlines.", "Educational only.", Instant.now());
        return new ResearchStatusDto(id, "COMPLETED", Instant.now(), Instant.now(), Instant.now(),
                "Analyze AAPL", List.of("AAPL"), result, null);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void completedResearchRendersParseablePdf() throws Exception {
        UUID id = UUID.randomUUID();
        when(orchestrator.getResearch(id)).thenReturn(completed(id));

        byte[] pdf = service.generateReport(id);

        assertThat(pdf).isNotEmpty();
        String text = textOf(pdf);
        assertThat(text).contains("AAPL").contains("150.25").contains("Technology")
                .contains("MODERATE").contains("POSITIVE").contains("Analyze AAPL");
    }

    @Test
    void pendingRunningAndFailedAreRejected() {
        for (String status : List.of("PENDING", "RUNNING", "FAILED")) {
            UUID id = UUID.randomUUID();
            when(orchestrator.getResearch(id)).thenReturn(new ResearchStatusDto(
                    id, status, Instant.now(), null, null, "q", List.of("AAPL"), null, null));

            assertThatThrownBy(() -> service.generateReport(id))
                    .isInstanceOf(ReportNotReadyException.class)
                    .hasMessageContaining(status);
        }
    }

    @Test
    void unknownIdPropagatesNotFound() {
        UUID id = UUID.randomUUID();
        when(orchestrator.getResearch(id))
                .thenThrow(new ResourceNotFoundException("Research not found: " + id));

        assertThatThrownBy(() -> service.generateReport(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void generatorFailureMapsToSafe500() throws Exception {
        PdfReportGenerator failing = org.mockito.Mockito.mock(PdfReportGenerator.class);
        when(failing.generate(any())).thenThrow(new IOException("disk on fire"));
        ResearchReportService failingService = new ResearchReportService(orchestrator, failing);
        UUID id = UUID.randomUUID();
        when(orchestrator.getResearch(id)).thenReturn(completed(id));

        assertThatThrownBy(() -> failingService.generateReport(id))
                .isInstanceOf(ReportGenerationException.class);
    }
}
