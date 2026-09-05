package com.finagent.market.adapter;

import com.finagent.exception.ProviderException;
import com.finagent.exception.UnknownSymbolException;
import com.finagent.market.BenchmarkDataProvider;
import com.finagent.market.dto.HistoricalBar;
import com.finagent.market.dto.HistoricalSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory benchmark provider for offline development and tests.
 * The series is seeded from the benchmark symbol hash, so the same symbol always
 * produces the same bars. This is a dev/test utility - it is NOT used as the
 * source of real market data.
 *
 * <p>Special symbols: "UNKNOWN" -&gt; UnknownSymbolException, "FAIL" -&gt; ProviderException.</p>
 */
public class StubBenchmarkDataProvider implements BenchmarkDataProvider {

    @Override
    public HistoricalSeries getBenchmarkHistory(String benchmarkSymbol, LocalDate from, LocalDate to) {
        if ("UNKNOWN".equalsIgnoreCase(benchmarkSymbol)) {
            throw new UnknownSymbolException(benchmarkSymbol);
        }
        if ("FAIL".equalsIgnoreCase(benchmarkSymbol)) {
            throw new ProviderException("Stub benchmark provider simulated failure for " + benchmarkSymbol);
        }
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(90);

        int hash = Math.abs(benchmarkSymbol.hashCode());
        // Deterministic benchmark base around 400 (index-like).
        BigDecimal base = BigDecimal.valueOf(380 + (hash % 2000) / 100.0).setScale(2, RoundingMode.HALF_UP);

        List<HistoricalBar> bars = new ArrayList<>();
        LocalDate date = effectiveFrom;
        int step = 0;
        while (!date.isAfter(effectiveTo)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                BigDecimal close = base.add(BigDecimal.valueOf(((step % 7) - 3) * 0.4))
                        .setScale(2, RoundingMode.HALF_UP);
                bars.add(new HistoricalBar(date,
                        close.subtract(new BigDecimal("0.40")),
                        close.add(new BigDecimal("0.80")),
                        close.subtract(new BigDecimal("0.80")),
                        close,
                        5_000_000L + (hash % 400_000L)));
                step++;
            }
            date = date.plusDays(1);
        }
        return new HistoricalSeries(benchmarkSymbol, bars);
    }
}