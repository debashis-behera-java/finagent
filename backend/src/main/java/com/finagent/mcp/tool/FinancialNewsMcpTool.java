package com.finagent.mcp.tool;

import com.finagent.mcp.dto.FinancialNewsToolResponse;
import com.finagent.service.NewsDataService;
import com.finagent.service.NewsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;

/**
 * MCP tool {@code search_financial_news}: delegates to {@link NewsDataService}.
 * Pure news retrieval - for scored sentiment use {@code analyze_news_sentiment}.
 */
@Slf4j
public class FinancialNewsMcpTool {

    private final NewsDataService newsDataService;

    public FinancialNewsMcpTool(NewsDataService newsDataService) {
        this.newsDataService = newsDataService;
    }

    @Tool(name = "search_financial_news",
            description = "Search recent financial news for a stock or company ticker. Returns normalized articles "
                    + "(title, url, source, description, imageUrl, publishedAt). Optional inclusive ISO-8601 date "
                    + "range (default = last 7 days) and result limit (default 20, max 50). Pure retrieval — "
                    + "for scored sentiment call analyze_news_sentiment.")
    public FinancialNewsToolResponse searchFinancialNews(
            @ToolParam(description = "Stock ticker or company symbol, e.g. AAPL or MSFT") String symbol,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd (optional, default = last 7 days)", required = false) String from,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd (optional, default = today)", required = false) String to,
            @ToolParam(description = "Maximum number of articles, 1-50 (optional, default = 20)", required = false) Integer limit) {
        log.debug("MCP search_financial_news invoked for symbol={} from={} to={} limit={}", symbol, from, to, limit);
        LocalDate fromDate = DateParsers.parseIsoDate(from, "from");
        LocalDate toDate = DateParsers.parseIsoDate(to, "to");
        NewsResponse response = newsDataService.getRecentNews(symbol, fromDate, toDate, limit);
        log.debug("MCP search_financial_news resolved symbol={} articles={}", response.symbol(), response.count());
        return new FinancialNewsToolResponse(
                response.symbol(), fromDate, toDate, response.count(), response.articles());
    }
}