package com.finagent.mcp.tool;

import com.finagent.mcp.dto.NewsSentimentToolResponse;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.service.SentimentAnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;

/**
 * MCP tool {@code analyze_news_sentiment}: fetches news through the existing
 * {@link com.finagent.service.NewsDataService} (via {@link SentimentAnalysisService},
 * never directly from a provider) and returns deterministic lexicon sentiment.
 * Provider failures surface as an UNAVAILABLE sentiment with a reason — never as
 * a fabricated NEUTRAL.
 */
@Slf4j
public class NewsSentimentMcpTool {

    private final SentimentAnalysisService sentimentAnalysisService;

    public NewsSentimentMcpTool(SentimentAnalysisService sentimentAnalysisService) {
        this.sentimentAnalysisService = sentimentAnalysisService;
    }

    @Tool(name = "analyze_news_sentiment",
            description = "Deterministic news sentiment for a stock or company ticker: label "
                    + "(POSITIVE/NEUTRAL/NEGATIVE/UNAVAILABLE), score in [-1.0,+1.0], confidence, "
                    + "per-label article counts and methodology. Keyword baseline over retrieved "
                    + "headlines — explainable, offline, not professional NLP. Optional inclusive "
                    + "ISO-8601 date range (default = last 7 days) and result limit (default 20, max 50).")
    public NewsSentimentToolResponse analyzeNewsSentiment(
            @ToolParam(description = "Stock ticker or company symbol, e.g. AAPL or MSFT") String symbol,
            @ToolParam(description = "Inclusive start date yyyy-MM-dd (optional, default = last 7 days)", required = false) String from,
            @ToolParam(description = "Inclusive end date yyyy-MM-dd (optional, default = today)", required = false) String to,
            @ToolParam(description = "Maximum number of articles to analyze, 1-50 (optional, default = 20)", required = false) Integer limit) {
        log.debug("MCP analyze_news_sentiment invoked for symbol={} from={} to={} limit={}",
                symbol, from, to, limit);
        LocalDate fromDate = DateParsers.parseIsoDate(from, "from");
        LocalDate toDate = DateParsers.parseIsoDate(to, "to");
        SentimentResult sentiment = sentimentAnalysisService.analyzeSymbol(symbol, fromDate, toDate, limit);
        log.debug("MCP analyze_news_sentiment resolved symbol={} label={} analyzed={}",
                sentiment.symbol(), sentiment.label(), sentiment.analyzedCount());
        return new NewsSentimentToolResponse(sentiment.symbol(), fromDate, toDate,
                sentiment.articleCount(), sentiment);
    }
}
