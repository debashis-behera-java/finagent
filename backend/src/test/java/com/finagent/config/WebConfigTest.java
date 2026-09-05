package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11 browser contract: the React dashboard origin may call /api/**
 * (simple + preflight requests). No credentials, no wildcard origin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class WebConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dashboardOriginAllowedOnApi() throws Exception {
        mockMvc.perform(get("/api/v1/research")
                        .header("Origin", "http://localhost:5173")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void preflightAllowsGetAndPost() throws Exception {
        mockMvc.perform(options("/api/v1/research")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void unlistedOriginIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/research")
                        .header("Origin", "https://evil.example")
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
