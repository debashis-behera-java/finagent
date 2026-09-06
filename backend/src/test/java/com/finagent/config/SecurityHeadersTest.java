package com.finagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 17: HTTP security headers on API responses — content-type sniffing
 * off, framing denied, referrer policy set. Post-project hardening adds HSTS
 * (HTTPS responses only; asserted via a secure MockMvc request). CSP is
 * intentionally absent — the API serves JSON, and a CSP belongs on the nginx
 * static layer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SecurityHeadersTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    @Test
    void errorResponsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/research/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void plainHttpResponsesCarryNoHsts() throws Exception {
        // HSTS is meaningless (and ignored by browsers) over plain HTTP.
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void httpsResponsesCarryHsts() throws Exception {
        mockMvc.perform(get("/api/v1/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("includeSubDomains"))));
    }
}
