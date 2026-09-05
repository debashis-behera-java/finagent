package com.finagent.service;

import com.finagent.common.TickerNormalizer;
import com.finagent.market.MarketDataProvider;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Use-case layer for stock data retrieval: normalizes/validates symbols and delegates
 * to the active {@link MarketDataProvider}. No business logic beyond input hygiene.
 */
@Service
@Slf4j
public class StockDataService {

    private final MarketDataProvider marketDataProvider;

    public StockDataService(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    public Quote getQuote(String symbol) {
        return marketDataProvider.getQuote(TickerNormalizer.normalize(symbol));
    }

    public HistoricalSeries getHistory(String symbol, LocalDate from, LocalDate to) {
        String normalized = TickerNormalizer.normalize(symbol);
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(90);
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("'from' date must not be after 'to' date");
        }
        return marketDataProvider.getHistory(normalized, effectiveFrom, effectiveTo);
    }

    public Fundamentals getFundamentals(String symbol) {
        return marketDataProvider.getFundamentals(TickerNormalizer.normalize(symbol));
    }

    /** Combined quote + fundamentals for the Phase 3 test endpoint. */
    public StockResponse getStockOverview(String symbol) {
        String normalized = TickerNormalizer.normalize(symbol);
        log.debug("Fetching stock overview for {}", normalized);
        Quote quote = marketDataProvider.getQuote(normalized);
        Fundamentals fundamentals = marketDataProvider.getFundamentals(normalized);
        return new StockResponse(normalized, quote, fundamentals);
    }
}
