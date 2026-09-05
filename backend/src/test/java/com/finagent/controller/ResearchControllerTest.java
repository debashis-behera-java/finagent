package com.finagent.controller;

import com.finagent.agent.ResearchAgent;
import com.finagent.agent.model.AgentContext;
import com.finagent.dto.response.ResearchStatusDto;
import com.finagent.repository.ResearchRequestRepository;
import com.finagent.service.ResearchOrchestrator;
import com.finagent.support.AgentResultFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 API tests (MockMvc, mocked agent, H2): 202 submit shape, validation 400s,
 * 404s, status/result/error shapes, and paged history.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class ResearchControllerTest {

    @MockBean
    private ResearchAgent researchAgent;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResearchOrchestrator orchestrator;

    @Autowired
    private ResearchRequestRepository requests;

    @BeforeEach
    void cleanAndReset() {
        requests.deleteAll();
        reset(researchAgent);
        when(researchAgent.run(any(AgentContext.class)))
                .thenAnswer(inv -> AgentResultFixtures.canned("AAPL"));
    }

    @AfterEach
    void cleanAfter() {
        requests.deleteAll();
    }

    @Test
    void postReturns202AcceptedShape() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"Analyze AAPL for a moderate-risk investor.","tickers":["AAPL"]}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.researchId").isString())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void postAcceptsQueryOnly() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"Research $AAPL please"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postBlankQueryReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"  "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void postInvalidTickerReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"Research it","tickers":["BAD!SYMBOL"]}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownIdReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/research/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getMalformedIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/research/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCompletedReturnsResultWithoutError() throws Exception {
        String id = submitAndGetId("""
                {"query":"Analyze AAPL.","tickers":["AAPL"]}""");
        awaitTerminal(UUID.fromString(id));

        mockMvc.perform(get("/api/v1/research/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.researchId").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.tickers[0]").value("AAPL"))
                .andExpect(jsonPath("$.result.interpretation").isString())
                .andExpect(jsonPath("$.result.executiveSummary").isString())
                .andExpect(jsonPath("$.result.metrics.tickers[0]").value("AAPL"))
                .andExpect(jsonPath("$.result.newsSummary").isString())
                .andExpect(jsonPath("$.result.disclaimer").isString())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void getFailedReturnsErrorWithoutResult() throws Exception {
        reset(researchAgent);
        when(researchAgent.run(any(AgentContext.class)))
                .thenThrow(new com.finagent.exception.FactBundleIncompleteException("no facts"));
        String id = submitAndGetId("""
                {"query":"Research it.","tickers":["FAIL"]}""");
        awaitTerminal(UUID.fromString(id));

        mockMvc.perform(get("/api/v1/research/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("NO_FACTS"))
                .andExpect(jsonPath("$.error.message").isString())
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void historyIsPagedNewestFirst() throws Exception {
        String first = submitAndGetId("""
                {"query":"first"}""");
        String second = submitAndGetId("""
                {"query":"second"}""");
        String third = submitAndGetId("""
                {"query":"third"}""");
        awaitTerminal(UUID.fromString(first));
        awaitTerminal(UUID.fromString(second));
        awaitTerminal(UUID.fromString(third));

        mockMvc.perform(get("/api/v1/research").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].researchId").value(third))
                .andExpect(jsonPath("$.content[1].researchId").value(second));

        mockMvc.perform(get("/api/v1/research").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].researchId").value(first));
    }

    @Test
    void invalidPagingReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/research").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/research").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/research").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rapidResearchSubmissionsAreRateLimitedTo429() throws Exception {
        // Dedicated client IP: the default budget is 30/min, so the 31st
        // submission from this IP is rejected without touching other tests.
        String body = "{\"query\":\"Analyze AAPL.\",\"tickers\":[\"AAPL\"]}";
        MvcResult last = null;
        for (int i = 0; i < 31; i++) {
            last = mockMvc.perform(post("/api/v1/research")
                            .with(request -> {
                                request.setRemoteAddr("10.0.7.7");
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        }
        org.assertj.core.api.Assertions.assertThat(last).isNotNull();
        org.assertj.core.api.Assertions.assertThat(last.getResponse().getStatus()).isEqualTo(429);
        // Phase 17: the filter-rendered 429 keeps the ApiError shape, no internals.
        org.assertj.core.api.Assertions.assertThat(last.getResponse().getContentAsString())
                .contains("\"status\":429")
                .doesNotContain("stacktrace");
    }

    private String submitAndGetId(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/research")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.replaceAll(".*\"researchId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private void awaitTerminal(UUID id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            ResearchStatusDto dto = orchestrator.getResearch(id);
            if (dto.status().equals("COMPLETED") || dto.status().equals("FAILED")) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("Timed out waiting for terminal status on " + id);
    }
}
