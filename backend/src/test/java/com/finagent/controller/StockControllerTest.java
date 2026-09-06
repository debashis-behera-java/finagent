package com.finagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 endpoint tests: GET /api/v1/stocks/{symbol} through the stub provider.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class StockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStockReturnsQuoteAndFundamentals() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/aapl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.quote.price").isNumber())
                .andExpect(jsonPath("$.quote.symbol").value("AAPL"))
                .andExpect(jsonPath("$.fundamentals.companyName").isNotEmpty())
                .andExpect(jsonPath("$.fundamentals.sector").isNotEmpty());
    }

    @Test
    void invalidSymbolReturns400WithApiError() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/INVALID!SYMBOL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").isNotEmpty());
    }

    @Test
    void unknownSymbolReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/UNKNOWN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Unknown symbol: UNKNOWN"));
    }

    @Test
    void getNewsReturnsStructuredJson() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/aapl/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.count").isNumber())
                .andExpect(jsonPath("$.articles").isArray())
                .andExpect(jsonPath("$.articles[0].title").isNotEmpty())
                .andExpect(jsonPath("$.articles[0].url").isNotEmpty())
                .andExpect(jsonPath("$.articles[0].source").isNotEmpty())
                .andExpect(jsonPath("$.articles[0].description").isNotEmpty())
                .andExpect(jsonPath("$.articles[0].imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.articles[0].publishedAt").isNotEmpty());
    }

    @Test
    void getNewsRespectsLimitParameter() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/AAPL/news").param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.articles.length()").value(3));
    }

    @Test
    void getNewsInvalidSymbolReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/INVALID!X/news"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    void getNewsUnknownSymbolReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/UNKNOWN/news"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown symbol: UNKNOWN"));
    }

    @Test
    void getNewsProviderFailureReturns502() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/FAIL/news"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Bad Gateway"));
    }

    @Test
    void providerFailureReturns502() throws Exception {
        mockMvc.perform(get("/api/v1/stocks/FAIL"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.error").value("Bad Gateway"));
    }
}
