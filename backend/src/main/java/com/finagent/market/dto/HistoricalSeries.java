package com.finagent.market.dto;

import java.util.List;

/** Historical daily series for a symbol, bars sorted ascending by date. */
public record HistoricalSeries(
        String symbol,
        List<HistoricalBar> bars
) {

    public HistoricalSeries {
        bars = List.copyOf(bars);
    }
}
