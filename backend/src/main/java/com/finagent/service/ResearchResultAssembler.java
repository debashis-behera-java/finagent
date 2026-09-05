package com.finagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finagent.agent.model.AgentResult;
import com.finagent.agent.model.FactEntry;
import com.finagent.model.ResearchRequest;
import com.finagent.model.ResearchResult;
import com.finagent.news.sentiment.SentimentResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Translates an in-memory {@link AgentResult} into the persisted {@link ResearchResult}
 * row (Phase 8). Stores facts, guarded interpretation, deterministic risk metrics,
 * news summary, limitations (data gaps) and the disclaimer — and nothing else: no raw
 * prompts, no model chain-of-thought, no secrets.
 */
final class ResearchResultAssembler {

    static final String DISCLAIMER =
            "FinAgent provides educational investment research, not financial advice. "
            + "Metrics are computed deterministically from retrieved market data; "
            + "interpretations are AI-generated prose and may be incomplete.";

    private ResearchResultAssembler() {
    }

    static ResearchResult assemble(ResearchRequest request, AgentResult result, ObjectMapper objectMapper) {
        ResearchResult stored = new ResearchResult();
        stored.setRequest(request);
        stored.setInterpretation(result.interpretation());
        stored.setExecutiveSummary(executiveSummary(result));
        stored.setSentimentSummary(newsSummary(result));
        stored.setMetricsSnapshot(metricsSnapshot(result, objectMapper));
        request.setResult(stored);
        return stored;
    }

    private static String executiveSummary(AgentResult result) {
        List<String> tickers = result.factBundle().tickers();
        StringJoiner joiner = new StringJoiner(" | ");
        for (FactEntry entry : result.factBundle().entries()) {
            joiner.add(describeEntry(entry));
        }
        String summary = "Research on " + String.join(", ", tickers) + ". " + joiner;
        if (!result.guardResult().passed()) {
            summary += ". Safety guard redacted "
                    + result.guardResult().ungroundedFigures().size() + " ungrounded figure(s).";
        }
        return summary;
    }

    private static String describeEntry(FactEntry entry) {
        List<String> parts = new ArrayList<>();
        parts.add(entry.symbol());
        if (entry.quote() != null && entry.quote().price() != null) {
            parts.add("price " + entry.quote().price());
        } else {
            parts.add("quote unavailable");
        }
        if (entry.riskAnalysis() != null
                && entry.riskAnalysis().riskScore() != null
                && entry.riskAnalysis().riskScore().available()) {
            parts.add("risk " + entry.riskAnalysis().riskScore().category()
                    + " (" + entry.riskAnalysis().riskScore().score() + ")");
        } else {
            parts.add("risk unavailable");
        }
        parts.add(entry.historyBarCount() + " bars");
        parts.add(entry.newsCount() + " headlines");
        if (entry.newsSentiment() != null && entry.newsSentiment().isAvailable()) {
            parts.add("sentiment " + entry.newsSentiment().label()
                    + " (" + entry.newsSentiment().score() + ")");
        }
        if (!entry.dataGaps().isEmpty()) {
            parts.add("gaps: " + String.join("; ", entry.dataGaps()));
        }
        return String.join(", ", parts);
    }

    /** Factual news summary (headline counts + sentiment signal) — no sentiment invention. */
    private static String newsSummary(AgentResult result) {
        StringJoiner joiner = new StringJoiner(" | ");
        for (FactEntry entry : result.factBundle().entries()) {
            String headlines;
            if (entry.newsHeadlines().isEmpty()) {
                headlines = entry.symbol() + ": no headlines found";
            } else {
                headlines = entry.symbol() + " (" + entry.newsCount() + " headlines, latest: "
                        + entry.newsHeadlines().get(0) + ")";
            }
            SentimentResult sentiment = entry.newsSentiment();
            if (sentiment != null) {
                if (sentiment.isAvailable()) {
                    headlines += "; sentiment " + sentiment.label()
                            + " (score " + sentiment.score()
                            + ", " + sentiment.positiveCount() + "+/"
                            + sentiment.neutralCount() + "=/" + sentiment.negativeCount() + "-"
                            + " of " + sentiment.analyzedCount() + " analyzed)";
                } else {
                    headlines += "; sentiment unavailable (" + sentiment.unavailableReason() + ")";
                }
            }
            joiner.add(headlines);
        }
        return joiner.toString();
    }

    private static String metricsSnapshot(AgentResult result, ObjectMapper objectMapper) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putPOJO("tickers", result.factBundle().tickers());
        root.put("generatedAt", result.factBundle().generatedAt().toString());
        ArrayNode entries = root.putArray("entries");
        for (FactEntry entry : result.factBundle().entries()) {
            entries.add(entryNode(entry, objectMapper));
        }
        ObjectNode guard = root.putObject("guard");
        guard.put("passed", result.guardResult().passed());
        guard.put("redactedCount", result.guardResult().ungroundedFigures().size());
        root.put("disclaimer", DISCLAIMER);
        return root.toString();
    }

    private static ObjectNode entryNode(FactEntry entry, ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", entry.symbol());
        if (entry.quote() != null) {
            putDecimal(node, "price", entry.quote().price());
            putDecimal(node, "change", entry.quote().change());
            putDecimal(node, "changePercent", entry.quote().changePercent());
        } else {
            node.putNull("price");
        }
        if (entry.fundamentals() != null) {
            node.put("companyName", entry.fundamentals().companyName());
            node.put("sector", entry.fundamentals().sector());
            putDecimal(node, "marketCap", entry.fundamentals().marketCap());
            putDecimal(node, "peRatio", entry.fundamentals().peRatio());
        }
        node.put("historyBars", entry.historyBarCount());
        node.put("newsCount", entry.newsCount());
        ArrayNode headlines = node.putArray("headlines");
        entry.newsHeadlines().forEach(headlines::add);
        node.set("sentiment", sentimentNode(entry.newsSentiment(), objectMapper));
        ObjectNode risk = node.putObject("risk");
        if (entry.riskAnalysis() != null) {
            var analysis = entry.riskAnalysis();
            putDouble(risk, "volatility", availableValue(analysis.volatility()));
            putDouble(risk, "maxDrawdown", availableValue(analysis.maxDrawdown()));
            putDouble(risk, "beta", availableValue(analysis.beta()));
            putDouble(risk, "sharpe", availableValue(analysis.sharpe()));
            if (analysis.riskScore() != null && analysis.riskScore().available()) {
                putDouble(risk, "score", analysis.riskScore().score());
                risk.put("category", analysis.riskScore().category().name());
            } else {
                risk.putNull("score");
            }
        } else {
            risk.putNull("score");
        }
        ArrayNode gaps = node.putArray("gaps");
        entry.dataGaps().forEach(gaps::add);
        return node;
    }

    /**
     * Structured sentiment for the metrics snapshot: label, score, confidence,
     * counts and methodology. {@code status} distinguishes "not-analyzed"
     * (run did not ask for sentiment) from "unavailable" (asked, no usable signal).
     */
    private static ObjectNode sentimentNode(SentimentResult sentiment, ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        if (sentiment == null) {
            node.put("status", "not-analyzed");
            return node;
        }
        if (!sentiment.isAvailable()) {
            node.put("status", "unavailable");
            node.put("reason", sentiment.unavailableReason().name());
            node.put("methodology", sentiment.methodology());
            return node;
        }
        node.put("status", "available");
        node.put("label", sentiment.label().name());
        putDouble(node, "score", sentiment.score());
        node.put("confidence", sentiment.confidence());
        node.put("articleCount", sentiment.articleCount());
        node.put("analyzedCount", sentiment.analyzedCount());
        node.put("unavailableCount", sentiment.unavailableCount());
        node.put("positiveCount", sentiment.positiveCount());
        node.put("neutralCount", sentiment.neutralCount());
        node.put("negativeCount", sentiment.negativeCount());
        node.put("methodology", sentiment.methodology());
        return node;
    }

    private static Double availableValue(com.finagent.analysis.model.VolatilityResult r) {
        return r != null && r.available() ? r.annualizedVolatility() : null;
    }

    private static Double availableValue(com.finagent.analysis.model.MaxDrawdownResult r) {
        return r != null && r.available() ? r.maxDrawdown() : null;
    }

    private static Double availableValue(com.finagent.analysis.model.BetaResult r) {
        return r != null && r.available() ? r.beta() : null;
    }

    private static Double availableValue(com.finagent.analysis.model.SharpeRatioResult r) {
        return r != null && r.available() ? r.sharpeRatio() : null;
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putDouble(ObjectNode node, String field, Double value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }
}
