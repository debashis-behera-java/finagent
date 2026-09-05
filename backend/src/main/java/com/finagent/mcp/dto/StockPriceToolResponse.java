package com.finagent.mcp.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * MCP tool response DTO for {@code get_stock_price}.
 * Clean internal shape - never leaks provider-specific structures.
 */
public record StockPriceToolResponse(
        String symbol,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changePercent,
        Instant timestamp
) {
}