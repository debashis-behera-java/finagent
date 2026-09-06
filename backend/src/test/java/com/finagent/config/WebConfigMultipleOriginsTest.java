package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Task 1 (production profile verification): the CORS origin list is a real
 * multi-value configuration — every configured origin is echoed, anything
 * else (including "*") is rejected. Production relies on this by setting
 * {@code FINAGENT_CORS_ALLOWED_ORIGINS} to its public origins.
 */
@SpringBootTest(properties = "finagent.app.cors-allowed-origins=https://app.example.com,https://admin.example.com")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class WebConfigMultipleOriginsTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com", "https://admin.example.com"})
    void eachConfiguredOriginIsAllowed(String origin) throws Exception {
        mockMvc.perform(get("/api/v1/research")
                        .header("Origin", origin)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
    }

    @Test
    void preflightSucceedsForSecondaryOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/research")
                        .header("Origin", "https://admin.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "*", "http://localhost:5173"})
    void unlistedOriginsAreRejected(String origin) throws Exception {
        mockMvc.perform(get("/api/v1/research")
                        .header("Origin", origin)
                        .param("page", "0").param("size", "1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
