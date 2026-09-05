package com.finagent.market;

import com.finagent.market.dto.HistoricalSeries;

import java.time.LocalDate;

/**
 * PORT: swappable benchmark data source for the deterministic risk engine
 * (beta calculation).
 *
 * <p>Benchmark prices follow the same contract as {@link MarketDataProvider}
 * history: a {@link HistoricalSeries} with daily bars in the inclusive
 * [from, to] range. Implementations currently: deterministic stub (dev/tests).
 * A real adapter (e.g. re-using the Alpha Vantage adapter for an index symbol
 * such as SPY or ^GSPC) can be connected later without touching business code -
 * see docs/risk-methodology.md.</p>
 */
public interface BenchmarkDataProvider {

    /**
     * Daily historical bars for the benchmark symbol within [from, to] (inclusive),
     * sorted ascending by date.
     */
    HistoricalSeries getBenchmarkHistory(String benchmarkSymbol, LocalDate from, LocalDate to);
}