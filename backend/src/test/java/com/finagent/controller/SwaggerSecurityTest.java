package com.finagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16: Swagger surface switch — denied when
 * {@code finagent.swagger.enabled=false} (production posture).
 */
@SpringBootTest(properties = "finagent.swagger.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SwaggerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void swaggerUiIsDeniedWhenDisabled() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isForbidden());
    }

    @Test
    void apiDocsAreDeniedWhenDisabled() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isForbidden());
    }
}
