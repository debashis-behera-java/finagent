package com.finagent.controller;

import com.finagent.auth.JwtService;
import com.finagent.config.FinAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16: enforcement matrix — public stays public, protected rejects
 * anonymous/malformed/expired/foreign-key tokens with safe JSON bodies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthEnforcementTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void actuatorHealthStaysPublicForContainerProbes() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointsRejectAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/AAPL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        mockMvc.perform(get("/api/v1/research"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/AAPL")
                        .header("Authorization", "Bearer garbage.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void missingBearerSchemeIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/AAPL")
                        .header("Authorization", "Token abc123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        FinAgentProperties props = new FinAgentProperties();
        props.getAuth().setJwtSecret("test-only-jwt-secret-for-automated-tests-not-production-0123456789");
        props.getAuth().setTokenTtl(Duration.ofMillis(1));
        JwtService shortLived = new JwtService(props);
        String token = shortLived.createToken(UUID.randomUUID(), "a@example.com", "USER");
        Thread.sleep(20);

        mockMvc.perform(get("/api/v1/stocks/AAPL")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignKeyTokenIsRejected() throws Exception {
        FinAgentProperties props = new FinAgentProperties();
        props.getAuth().setJwtSecret("another-test-only-secret-that-is-long-enough-0123456789");
        String foreign = new JwtService(props)
                .createToken(UUID.randomUUID(), "a@example.com", "USER");

        mockMvc.perform(get("/api/v1/stocks/AAPL")
                        .header("Authorization", "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void errorResponsesCarryNoSensitiveMaterial() throws Exception {
        String body = mockMvc.perform(get("/api/v1/stocks/AAPL"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assert body != null;
        String lower = body.toLowerCase();
        // "Bearer" appears only in the guidance message ("provide a valid Bearer
        // access token") — that is instruction, not credential material.
        for (String leak : new String[]{"stacktrace", "exception", "finagent.auth",
                "secret", "password", "apikey", "api_key", "at com.finagent"}) {
            if (lower.contains(leak)) {
                throw new AssertionError("Leak in 401 body: " + leak + " -> " + body);
            }
        }
    }
}
