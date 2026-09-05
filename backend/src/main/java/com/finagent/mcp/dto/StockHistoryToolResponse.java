package com.finagent.mcp.dto;

import com.finagent.market.dto.HistoricalBar;

import java.time.LocalDate;
import java.util.List;

/**
 * MCP tool response DTO for {@code get_stock_history}. Reuses the shared
 * {@link HistoricalBar} domain DTO for each bar.
 */
public record StockHistoryToolResponse(
        String symbol,
        LocalDate from,
        LocalDate to,
        int barCount,
        List<HistoricalBar> bars
) {

    public StockHistoryToolResponse {
        bars = List.copyOf(bars);
    }
}