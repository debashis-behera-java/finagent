package com.finagent.controller;

import com.finagent.service.NewsDataService;
import com.finagent.service.NewsResponse;
import com.finagent.service.StockDataService;
import com.finagent.service.StockResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Phase 3/4 test endpoints for stock & news retrieval.
 * (Full API v1 layer arrives in Phase 9.)
 */
@RestController
@RequestMapping("/api/v1/stocks")
@Validated
@Tag(name = "Stocks", description = "Market data & news retrieval (Phases 3-4)")
public class StockController {

    private final StockDataService stockDataService;
    private final NewsDataService newsDataService;

    public StockController(StockDataService stockDataService, NewsDataService newsDataService) {
        this.stockDataService = stockDataService;
        this.newsDataService = newsDataService;
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "Current quote + company fundamentals for a stock")
    public StockResponse getStock(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,12}$",
                     message = "symbol must be 1-12 characters: letters, digits, '.' or '-'")
            String symbol) {
        return stockDataService.getStockOverview(symbol);
    }

    @GetMapping("/{symbol}/news")
    @Operation(summary = "Recent news headlines for a stock/company")
    public NewsResponse getNews(
            @PathVariable
            @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,12}$",
                     message = "symbol must be 1-12 characters: letters, digits, '.' or '-'")
            String symbol,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false)
            Integer limit) {
        return newsDataService.getRecentNews(symbol, from, to, limit);
    }
}
