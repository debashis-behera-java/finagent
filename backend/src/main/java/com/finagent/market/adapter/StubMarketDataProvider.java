package com.finagent.market.adapter;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.market.MarketDataProvider;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory provider for offline development and tests.
 * Same symbol always produces the same data (seeded from the symbol hash).
 * Special symbols: "UNKNOWN" -> UnknownSymbolException, "FAIL" -> ProviderException.
 */
public class StubMarketDataProvider implements MarketDataProvider {

    private static final BigDecimal MAX_PRICE = new BigDecimal("10000");

    @Override
    public Quote getQuote(String symbol) {
        failForSpecialSymbols(symbol);
        BigDecimal price = basePrice(symbol);
        BigDecimal previousClose = price.subtract(deterministicDelta(symbol, 1));
        BigDecimal change = price.subtract(previousClose);
        BigDecimal changePercent = change.divide(previousClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return new Quote(symbol, price, change, changePercent, Instant.now());
    }

    @Override
    public HistoricalSeries getHistory(String symbol, LocalDate from, LocalDate to) {
        failForSpecialSymbols(symbol);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(90);

        List<HistoricalBar> bars = new ArrayList<>();
        BigDecimal base = basePrice(symbol);
        LocalDate date = effectiveFrom;
        int step = 0;
        while (!date.isAfter(effectiveTo)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                BigDecimal close = base.add(BigDecimal.valueOf(((step % 7) - 3) * 0.5))
                        .setScale(2, RoundingMode.HALF_UP);
                bars.add(new HistoricalBar(date,
                        close.subtract(new BigDecimal("0.50")),
                        close.add(new BigDecimal("1.00")),
                        close.subtract(new BigDecimal("1.00")),
                        close,
                        1_000_000L + (Math.abs(symbol.hashCode()) % 900_000L)));
                step++;
            }
            date = date.plusDays(1);
        }
        return new HistoricalSeries(symbol, bars);
    }

    @Override
    public Fundamentals getFundamentals(String symbol) {
        failForSpecialSymbols(symbol);
        int hash = Math.abs(symbol.hashCode());
        String[] sectors = {"Technology", "Healthcare", "Financials", "Consumer Discretionary", "Energy"};
        return new Fundamentals(
                symbol,
                symbol + " Corp.",
                sectors[hash % sectors.length],
                "Stub Industry",
                MAX_PRICE.multiply(BigDecimal.valueOf(1_000_000 + (hash % 900_000L))),
                new BigDecimal("15").add(BigDecimal.valueOf(hash % 2000).divide(BigDecimal.valueOf(100))),
                new BigDecimal("1.25"),
                new BigDecimal("6.13"),
                "Deterministic stub fundamentals for " + symbol + " (FINAGENT offline mode).");
    }

    /** Deterministic price in [20, 420] derived from the symbol. */
    private BigDecimal basePrice(String symbol) {
        int hash = Math.abs(symbol.hashCode());
        return BigDecimal.valueOf(20 + (hash % 40_000) / 100.0).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal deterministicDelta(String symbol, int salt) {
        return BigDecimal.valueOf((Math.abs(symbol.hashCode() + salt) % 500) / 100.0)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private void failForSpecialSymbols(String symbol) {
        if ("UNKNOWN".equalsIgnoreCase(symbol)) {
            throw new UnknownSymbolException(symbol);
        }
        if ("FAIL".equalsIgnoreCase(symbol)) {
            throw new ProviderException("Stub provider simulated failure for symbol " + symbol);
        }
    }
}
