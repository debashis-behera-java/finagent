package com.finagent.service;

import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.Quote;

/** Response body of GET /api/v1/stocks/{symbol}. */
public record StockResponse(
        String symbol,
        Quote quote,
        Fundamentals fundamentals
) {
}
