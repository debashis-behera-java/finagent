package com.finagent.controller;

import com.finagent.dto.request.ResearchSubmitRequest;
import com.finagent.dto.response.ResearchAcceptedDto;
import com.finagent.dto.response.ResearchHistoryDto;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.service.ResearchOrchestrator;
import com.finagent.service.ResearchReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Research workflow endpoints (Phase 8): submit a job (202 + id), poll its status,
 * and browse history newest-first. Thin by design — all lifecycle logic lives in
 * {@link ResearchOrchestrator}.
 *
 * <p>Absent without a database (dev profile): same tradeoff as Phase 1.</p>
 */
@RestController
@RequestMapping("/api/v1/research")
@Validated
@ConditionalOnProperty(prefix = "finagent.research", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Tag(name = "Research", description = "Research orchestration & history (Phase 8)")
public class ResearchController {

    private final ResearchOrchestrator orchestrator;
    private final ResearchReportService reportService;

    public ResearchController(ResearchOrchestrator orchestrator, ResearchReportService reportService) {
        this.orchestrator = orchestrator;
        this.reportService = reportService;
    }

    @PostMapping
    @Operation(summary = "Submit a research request (async, 202 + researchId)")
    public ResponseEntity<ResearchAcceptedDto> submit(@Valid @RequestBody ResearchSubmitRequest request) {
        ResearchAcceptedDto accepted = orchestrator.submitResearch(request.query(), request.tickers());
        return ResponseEntity.accepted().body(accepted);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Research status + result (when COMPLETED) or error (when FAILED)")
    public ResearchStatusDto get(@PathVariable UUID id) {
        return orchestrator.getResearch(id);
    }

    @GetMapping
    @Operation(summary = "Research history, newest first (paged)")
    public ResearchHistoryDto history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orchestrator.listHistory(PageRequest.of(page, size));
    }

    @GetMapping("/{id}/report.pdf")
    @Operation(summary = "Download the completed research as a PDF report "
            + "(404 unknown id, 409 not COMPLETED)")
    public ResponseEntity<byte[]> report(@PathVariable UUID id) {
        byte[] pdf = reportService.generateReport(id);
        // Filename is derived from the path UUID only — no user-controlled paths.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"finagent-research-" + id + ".pdf\"")
                .contentLength(pdf.length)
                .body(pdf);
    }
}
