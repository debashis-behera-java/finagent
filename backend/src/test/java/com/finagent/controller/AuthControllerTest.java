package com.finagent.controller;

import com.finagent.repository.RefreshTokenRepository;
import com.finagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16: auth endpoint contract on H2 — register/login/me shapes, duplicate
 * and validation rejections, generic login failures, token gating, 429.
 *
 * <p>Rate-limit isolation: each test uses a distinct client IP (MockMvc
 * remote address), so budgets never leak between tests; the 429 test reuses
 * one IP for 11 rapid attempts against the default budget of 10.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    private String email;
    private String clientIp;

    @BeforeEach
    void freshIdentity() {
        email = "user-" + UUID.randomUUID() + "@example.com";
        clientIp = "10.0.0." + IP_SEQ.getAndIncrement();
        // Task 2: refresh rows reference users — delete children first.
        refreshTokens.deleteAll();
        users.deleteAll();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult register(String email, String password, String ip, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    @Test
    void registerSucceedsWithTokenAndUserRole() throws Exception {
        String fresh = "fresh-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp("10.0.9.1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + fresh + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.user.email").value(fresh))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void duplicateRegistrationReturns409() throws Exception {
        register(email, "Secret123", clientIp, 201);

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"Secret123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void weakPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void roleCannotBeSelfAssigned() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\",\"role\":\"ADMIN\"}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertThat(status).isIn(201, 400);
        if (status == 201) {
            String body = result.getResponse().getContentAsString();
            assertThat(body).contains("\"role\":\"USER\"").doesNotContain("\"ADMIN\"");
        }
    }

    @Test
    void loginSucceedsAndMeReturnsProfile() throws Exception {
        register(email, "Secret123", clientIp, 201);

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void invalidCredentialsAreGenericAndIdentical() throws Exception {
        register(email, "Secret123", clientIp, 201);

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteIp(clientIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Wrong9999\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .with(remoteIp("10.0.9.2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost-" + UUID.randomUUID() + "@example.com\",\"password\":\"Secret123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andReturn();

        assertThat(unknownEmail.getResponse().getContentAsString())
                .contains("\"message\":\"Invalid email or password\"");
        assertThat(wrongPassword.getResponse().getContentAsString()).doesNotContain("Secret123");
    }

    @Test
    void meWithoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    void meWithMalformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void repeatedAuthAttemptsAreRateLimitedTo429() throws Exception {
        String ip = "10.0.9.9";
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";
        int lastStatus = 200;
        for (int i = 0; i < 11; i++) {
            lastStatus = mockMvc.perform(post("/api/v1/auth/login")
                            .with(remoteIp(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + ghost + "\",\"password\":\"Secret123\"}"))
                    .andReturn().getResponse().getStatus();
        }
        // Default budget is 10/min: the 11th attempt is rejected before validation.
        assertThat(lastStatus).isEqualTo(429);
    }
}
