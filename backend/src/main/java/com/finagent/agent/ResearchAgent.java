package com.finagent.agent;

import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.model.GuardResult;
import com.finagent.agent.model.ResearchPlan;
import com.finagent.analysis.model.RiskAnalysisResult;
import com.finagent.exception.FactBundleIncompleteException;
import com.finagent.market.dto.Fundamentals;
import com.finagent.market.dto.HistoricalSeries;
import com.finagent.market.dto.Quote;
import com.finagent.news.dto.NewsArticle;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import com.finagent.service.NewsDataService;
import com.finagent.service.NewsResponse;
import com.finagent.service.RiskAnalysisService;
import com.finagent.service.SentimentAnalysisService;
import com.finagent.service.StockDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Top-level AI research agent entrypoint (Phase 7).
 *
 * <pre>
 * run(request):
 *   1. PLAN      ResearchPlanner → ResearchPlan (deterministic, registry-constrained)
 *   2. EXECUTE   services per step (market / news / risk); per-source failures degrade
 *                into FactEntry data gaps instead of failing the run
 *   3. FACTS     FactBundleBuilder → immutable FactBundle (null/NaN/stale checks)
 *   4. SYNTHESIZE InterpretationSynthesizer: bundle in, prose out (LLM reasons only)
 *   5. GUARD     SafetyGuard: ungrounded figures redacted before the result leaves
 * </pre>
 *
 * <p>A run fails only when <i>no</i> ticker yields any usable facts
 * ({@link FactBundleIncompleteException}) or when synthesis itself fails. Persistence
 * of runs (PENDING → RUNNING → COMPLETED/FAILED rows) arrives with orchestration.</p>
 *
 * <p>News sentiment (Phase 9) is computed per ticker only when the request asks for
 * it (see {@link #wantsSentiment(String)}): a pure price question skips sentiment,
 * while sentiment/coverage/comprehensive requests include it as grounded facts.</p>
 */
@Component
@Slf4j
public class ResearchAgent {

    /**
     * Request-text signals that sentiment analysis is wanted. Deliberately narrow:
     * only explicit sentiment/news/coverage language or a comprehensive-analysis
     * ask triggers per-ticker sentiment — a plain price question must not.
     */
    static final Pattern SENTIMENT_REQUEST = Pattern.compile(
            "sentiment|mood|tone|coverage|headlines?|\\bnews\\b|comprehensive|thorough|"
            + "in-?depth|\\bfull\\b|\\bcomplete\\b|detailed|outlook|perception|\\bbuzz\\b",
            Pattern.CASE_INSENSITIVE);

    private final ResearchPlanner planner;
    private final FactBundleBuilder bundleBuilder;
    private final InterpretationSynthesizer synthesizer;
    private final SafetyGuard safetyGuard;
    private final StockDataService stockDataService;
    private final NewsDataService newsDataService;
    private final RiskAnalysisService riskAnalysisService;
    private final SentimentAnalysisService sentimentAnalysisService;

    public ResearchAgent(ResearchPlanner planner,
                         FactBundleBuilder bundleBuilder,
                         InterpretationSynthesizer synthesizer,
                         SafetyGuard safetyGuard,
                         StockDataService stockDataService,
                         NewsDataService newsDataService,
                         RiskAnalysisService riskAnalysisService,
                         SentimentAnalysisService sentimentAnalysisService) {
        this.planner = planner;
        this.bundleBuilder = bundleBuilder;
        this.synthesizer = synthesizer;
        this.safetyGuard = safetyGuard;
        this.stockDataService = stockDataService;
        this.newsDataService = newsDataService;
        this.riskAnalysisService = riskAnalysisService;
        this.sentimentAnalysisService = sentimentAnalysisService;
    }

    public AgentResult run(AgentContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Agent context must not be null");
        }
        ResearchPlan plan = planner.plan(context);
        log.info("Research run started: {} ticker(s) {} ({} planned steps)",
                plan.tickers().size(), plan.tickers(), plan.steps().size());

        List<FactEntry> entries = new ArrayList<>();
        List<String> failedTickers = new ArrayList<>();
        for (String ticker : plan.tickers()) {
            FactEntry entry = collectTicker(ticker, context);
            entries.add(entry);
            if (entry.isEmpty()) {
                failedTickers.add(ticker);
            }
        }
        if (entries.stream().allMatch(FactEntry::isEmpty)) {
            throw new FactBundleIncompleteException(
                    "All data sources failed for ticker(s) %s — no facts to interpret".formatted(failedTickers));
        }

        FactBundle bundle = bundleBuilder.buildBundle(context.requestText(), entries);
        String prose = synthesizer.synthesize(bundle);
        GuardResult guard = safetyGuard.check(prose, bundle);
        String interpretation = guard.passed() ? prose : guard.redactedText();
        if (!guard.passed()) {
            log.warn("Research run redacted {} ungrounded figure(s)", guard.ungroundedFigures().size());
        }
        log.info("Research run completed: {} entries, guard {}", entries.size(),
                guard.passed() ? "passed" : "redacted");
        return new AgentResult(bundle, interpretation, guard, Instant.now());
    }

    private FactEntry collectTicker(String ticker, AgentContext context) {
        Quote quote = null;
        Fundamentals fundamentals = null;
        HistoricalSeries history = null;
        List<NewsArticle> articles = null;
        boolean newsFailed = false;
        RiskAnalysisResult risk = null;

        try {
            quote = stockDataService.getQuote(ticker);
        } catch (RuntimeException ex) {
            log.warn("Quote collection failed for {}: {}", ticker, ex.getMessage());
        }
        try {
            history = stockDataService.getHistory(ticker, context.from(), context.to());
        } catch (RuntimeException ex) {
            log.warn("History collection failed for {}: {}", ticker, ex.getMessage());
        }
        try {
            fundamentals = stockDataService.getFundamentals(ticker);
        } catch (RuntimeException ex) {
            log.warn("Fundamentals collection failed for {}: {}", ticker, ex.getMessage());
        }
        try {
            NewsResponse news = newsDataService.getRecentNews(ticker, context.from(), context.to(), null);
            articles = news.articles();
        } catch (RuntimeException ex) {
            log.warn("News collection failed for {}: {}", ticker, ex.getMessage());
            newsFailed = true;
        }
        try {
            risk = riskAnalysisService.analyzeStockRisk(ticker, context.from(), context.to(), context.benchmark());
        } catch (RuntimeException ex) {
            log.warn("Risk analysis failed for {}: {}", ticker, ex.getMessage());
        }
        SentimentResult sentiment = analyzeSentiment(ticker, context, articles, newsFailed);
        return bundleBuilder.buildEntry(ticker, quote, fundamentals, history, articles, risk, sentiment);
    }

    /**
     * Computes news sentiment only when the request calls for it. A provider
     * failure yields an explicit UNAVAILABLE/PROVIDER_ERROR fact (never NEUTRAL);
     * skipped analysis yields {@code null} ("not analyzed for this run").
     */
    private SentimentResult analyzeSentiment(String ticker, AgentContext context,
                                             List<NewsArticle> articles, boolean newsFailed) {
        if (!wantsSentiment(context.requestText())) {
            return null;
        }
        if (newsFailed) {
            return SentimentResult.unavailable(ticker, UnavailableReason.PROVIDER_ERROR,
                    LexiconSentimentAnalyzer.METHODOLOGY);
        }
        try {
            return sentimentAnalysisService.analyzeNews(ticker, articles);
        } catch (RuntimeException ex) {
            log.warn("Sentiment analysis failed for {}: {}", ticker, ex.getMessage());
            return SentimentResult.unavailable(ticker, UnavailableReason.PROVIDER_ERROR,
                    LexiconSentimentAnalyzer.METHODOLOGY);
        }
    }

    /** Package-visible for unit tests. */
    static boolean wantsSentiment(String requestText) {
        return requestText != null && SENTIMENT_REQUEST.matcher(requestText).find();
    }
}
