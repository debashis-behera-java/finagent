package com.finagent.mcp.dto;

import com.finagent.market.dto.Fundamentals;

/**
 * MCP tool response DTO for {@code get_stock_fundamentals}. Wraps the
 * shared {@link Fundamentals} domain DTO.
 */
public record StockFundamentalsToolResponse(
        String symbol,
        Fundamentals fundamentals
) {
}