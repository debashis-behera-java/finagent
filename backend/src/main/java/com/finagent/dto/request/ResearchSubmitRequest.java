package com.finagent.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * POST /api/v1/research request body (Phase 8).
 */
public record ResearchSubmitRequest(
        @NotBlank(message = "query must not be blank")
        @Size(max = 2000, message = "query must be at most 2000 characters")
        String query,

        @Size(max = 10, message = "at most 10 tickers per request")
        List<@Pattern(regexp = "^[A-Za-z0-9.\\-]{1,12}$",
                message = "each ticker must be 1-12 characters: letters, digits, '.' or '-'")
                String> tickers) {

    public ResearchSubmitRequest {
        tickers = tickers == null ? List.of() : List.copyOf(tickers);
    }
}
