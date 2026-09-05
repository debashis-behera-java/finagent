package com.finagent.controller;

import com.finagent.config.FinAgentProperties;
import com.finagent.dto.response.HealthResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEMPORARY smoke-test endpoint (Phase 1).
 * Will be removed in Phase 9 when the real API v1 controllers land
 * (actuator /actuator/health remains the operational health probe).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Application smoke-test endpoint")
public class HealthController {

    private final FinAgentProperties properties;

    public HealthController(FinAgentProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/health")
    @Operation(summary = "Application health smoke test")
    public HealthResponse health() {
        return new HealthResponse(
                properties.getApp().getName(),
                properties.getApp().getVersion(),
                "RUNNING");
    }
}
