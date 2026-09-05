package com.finagent.agent;

import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.PlannedStep;
import com.finagent.agent.model.ResearchPlan;
import com.finagent.common.TickerNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the {@link ResearchPlan} for a run (Phase 7).
 *
 * <p>The planner is intentionally <b>rule-based, not LLM-based</b>: for every ticker it
 * emits the same five data-collection steps (price, history, fundamentals, news, risk),
 * constrained to the MCP tool whitelist owned by {@link ToolSelector}. Deterministic
 * planning keeps runs reproducible, needs no API key, and is trivially unit-testable.
 * An LLM may refine step ordering or depth in a later phase — it will never widen the
 * plan beyond the registry whitelist.</p>
 */
@Component
@Slf4j
public class ResearchPlanner {

    /**
     * Ticker mentions in free text. Only {@code $}-prefixed symbols (e.g. {@code $AAPL})
     * are extracted: bare English words ("compare ... and ...") also match the symbol
     * shape, so extracting them would silently research the wrong tickers. Callers
     * with structured input should pass explicit tickers instead.
     */
    private static final Pattern CANDIDATE_SYMBOL = Pattern.compile("\\$([A-Za-z0-9.\\-]{1,12})");

    /** Hard cap on tickers per run — bounds provider fan-out. */
    static final int MAX_TICKERS = 10;

    public ResearchPlan plan(AgentContext context) {
        if (context == null || context.requestText() == null || context.requestText().isBlank()) {
            throw new IllegalArgumentException("Research request text must not be blank");
        }
        List<String> tickers = resolveTickers(context);
        List<PlannedStep> steps = new ArrayList<>(tickers.size() * PlannedStep.StepType.values().length);
        for (String ticker : tickers) {
            steps.add(new PlannedStep(PlannedStep.StepType.GET_PRICE, ticker, ToolSelector.GET_STOCK_PRICE));
            steps.add(new PlannedStep(PlannedStep.StepType.GET_HISTORY, ticker, ToolSelector.GET_STOCK_HISTORY));
            steps.add(new PlannedStep(PlannedStep.StepType.GET_FUNDAMENTALS, ticker, ToolSelector.GET_STOCK_FUNDAMENTALS));
            steps.add(new PlannedStep(PlannedStep.StepType.SEARCH_NEWS, ticker, ToolSelector.SEARCH_FINANCIAL_NEWS));
            steps.add(new PlannedStep(PlannedStep.StepType.ANALYZE_RISK, ticker, ToolSelector.ANALYZE_STOCK_RISK));
        }
        log.debug("Planned research for {} ticker(s): {} ({} steps)", tickers.size(), tickers, steps.size());
        return new ResearchPlan(tickers, steps);
    }

    private List<String> resolveTickers(AgentContext context) {
        Set<String> tickers = new LinkedHashSet<>();
        if (context.tickers() != null) {
            for (String raw : context.tickers()) {
                tickers.add(TickerNormalizer.normalize(raw));
            }
        }
        if (tickers.isEmpty()) {
            tickers.addAll(extractFromText(context.requestText()));
        }
        if (tickers.isEmpty()) {
            throw new IllegalArgumentException(
                    "No tickers supplied and none found in the request text — specify at least one ticker symbol");
        }
        if (tickers.size() > MAX_TICKERS) {
            throw new IllegalArgumentException(
                    "Too many tickers: got %d, maximum is %d".formatted(tickers.size(), MAX_TICKERS));
        }
        return List.copyOf(tickers);
    }

    private Set<String> extractFromText(String requestText) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = CANDIDATE_SYMBOL.matcher(requestText);
        while (matcher.find()) {
            try {
                found.add(TickerNormalizer.normalize(matcher.group(1)));
            } catch (IllegalArgumentException ignored) {
                // Not a valid symbol shape — skip it.
            }
        }
        return found;
    }
}
