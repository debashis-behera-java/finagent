package com.finagent.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Latest quote for a symbol. */
public record Quote(
        String symbol,
        BigDecimal price,
        BigDecimal change,
        BigDecimal changePercent,
        Instant timestamp
) {
}
