package com.finagent.market.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day of OHLCV data. */
public record HistoricalBar(
        LocalDate date,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) {
}
