package com.finagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finagent.dto.response.ResearchResultDto;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.exception.ReportGenerationException;
import com.finagent.exception.ReportNotReadyException;
import com.finagent.report.PdfReportGenerator;
import com.finagent.report.ResearchReportData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PDF research-report export (Phase 10): presentation only.
 *
 * <p>Loads the already-persisted COMPLETED result, maps it once into a
 * {@link ResearchReportData} snapshot, and renders it with PDFBox. No new
 * research, no AI calls, no MCP calls, no external APIs, nothing written to
 * disk — the returned bytes are served straight to the HTTP response.</p>
 */
@Service
@ConditionalOnProperty(prefix = "finagent.research", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Slf4j
public class ResearchReportService {

    private final ResearchOrchestrator orchestrator;
    private final PdfReportGenerator generator;

    public ResearchReportService(ResearchOrchestrator orchestrator, PdfReportGenerator generator) {
        this.orchestrator = orchestrator;
        this.generator = generator;
    }

    /**
     * Renders the report for a COMPLETED research job.
     *
     * @throws ReportNotReadyException when the job is PENDING, RUNNING or FAILED
     * @throws ReportGenerationException when rendering itself fails (safe 500)
     */
    public byte[] generateReport(UUID researchId) {
        ResearchStatusDto status = orchestrator.getResearch(researchId);
        if (!"COMPLETED".equals(status.status()) || status.result() == null) {
            throw new ReportNotReadyException(
                    "Research %s is %s — the report is available when COMPLETED".formatted(
                            researchId, status.status()));
        }
        try {
            byte[] pdf = generator.generate(toReportData(status));
            log.info("Research {} report generated: {} bytes", researchId, pdf.length);
            return pdf;
        } catch (IOException | RuntimeException ex) {
            log.error("Research {} report generation failed: {}", researchId, ex.getMessage());
            throw new ReportGenerationException("Failed to generate report for " + researchId, ex);
        }
    }

    static ResearchReportData toReportData(ResearchStatusDto status) {
        ResearchResultDto result = status.result();
        List<ResearchReportData.Entry> entries = new ArrayList<>();
        JsonNode metricsEntries = result.metrics() != null
                ? result.metrics().path("entries") : null;
        if (metricsEntries != null && metricsEntries.isArray()) {
            for (JsonNode node : metricsEntries) {
                entries.add(toEntry(node));
            }
        }
        int redactions = result.metrics() != null
                ? result.metrics().path("guard").path("redactedCount").asInt(0) : 0;
        return new ResearchReportData(
                status.researchId(),
                status.requestText(),
                Instant.now(),
                status.completedAt(),
                result.tickers(),
                result.executiveSummary(),
                result.interpretation(),
                result.newsSummary(),
                result.disclaimer(),
                redactions,
                entries);
    }

    private static ResearchReportData.Entry toEntry(JsonNode node) {
        JsonNode risk = node.path("risk");
        JsonNode sentiment = node.path("sentiment");
        return new ResearchReportData.Entry(
                text(node, "symbol"),
                text(node, "companyName"),
                text(node, "price"),
                text(node, "change"),
                text(node, "changePercent"),
                text(node, "sector"),
                text(node, "marketCap"),
                text(node, "peRatio"),
                node.path("historyBars").asInt(0),
                node.path("newsCount").asInt(0),
                strings(node.path("headlines")),
                text(risk, "score"),
                text(risk, "category"),
                text(risk, "volatility"),
                text(risk, "maxDrawdown"),
                text(risk, "beta"),
                text(risk, "sharpe"),
                text(sentiment, "status"),
                text(sentiment, "label"),
                text(sentiment, "score"),
                text(sentiment, "confidence"),
                text(sentiment, "reason"),
                sentiment.path("articleCount").asInt(0),
                sentiment.path("analyzedCount").asInt(0),
                sentiment.path("positiveCount").asInt(0),
                sentiment.path("neutralCount").asInt(0),
                sentiment.path("negativeCount").asInt(0),
                text(sentiment, "methodology"),
                strings(node.path("gaps")));
    }

    /** Missing/null/blank JSON values map to null (= rendered as "Unavailable"). */
    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }
}
