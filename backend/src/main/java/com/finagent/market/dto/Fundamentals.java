package com.finagent.market.dto;

import java.math.BigDecimal;

/** Company fundamentals. Missing provider fields are null, never guessed. */
public record Fundamentals(
        String symbol,
        String companyName,
        String sector,
        String industry,
        BigDecimal marketCap,
        BigDecimal peRatio,
        BigDecimal dividendYield,
        BigDecimal eps,
        String description
) {
}
