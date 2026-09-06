package com.finagent.market;

import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;

import java.time.LocalDate;

/**
 * PORT: swappable market data source (Hexagonal). Implementations:
 * Alpha Vantage adapter (production), Stooq adapter, deterministic stub (dev/tests).
 * Implementations never leak their client types; all failures are translated into
 * {@link com.finagent.exception.ProviderException} / {@link com.finagent.exception.UnknownSymbolException}.
 */
public interface MarketDataProvider {

    /** Current/latest quote for the symbol. */
    Quote getQuote(String symbol);

    /** Daily historical bars for the symbol within [from, to] (inclusive), sorted ascending. */
    HistoricalSeries getHistory(String symbol, LocalDate from, LocalDate to);

    /** Company fundamentals for the symbol. */
    Fundamentals getFundamentals(String symbol);
}
