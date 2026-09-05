package com.finagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finagent.agent.model.AgentResult;
import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.model.GuardResult;
import com.finagent.market.dto.Quote;
import com.finagent.model.ResearchRequest;
import com.finagent.model.ResearchResult;
import com.finagent.news.sentiment.LexiconSentimentAnalyzer;
import com.finagent.news.sentiment.SentimentLabel;
import com.finagent.news.sentiment.SentimentResult;
import com.finagent.news.sentiment.UnavailableReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentiment persistence through the metrics snapshot (no new tables, no database).
 */
class ResearchResultAssemblerSentimentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static AgentResult resultWith(SentimentResult sentiment) {
        Quote quote = new Quote("AAPL", new BigDecimal("150.25"), BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
        FactEntry entry = new FactEntry("AAPL", quote, null, 5, null, null, 12,
                List.of("Record profits"), null, sentiment, List.of());
        FactBundle bundle = new FactBundle("Analyze sentiment", List.of("AAPL"), Instant.now(), List.of(entry));
        String prose = "Coverage reads positive. This is educational research, not financial advice.";
        return new AgentResult(bundle, prose, new GuardResult(true, List.of(), prose), Instant.now());
    }

    private static SentimentResult available() {
        return new SentimentResult("AAPL", SentimentLabel.POSITIVE, 0.6, 0.8,
                12, 12, 0, 7, 3, 2, null,
                LexiconSentimentAnalyzer.METHODOLOGY, List.of());
    }

    @Test
    void persistsLabelScoreCountsConfidenceAndMethodology() throws Exception {
        ResearchResult stored = ResearchResultAssembler.assemble(
                new ResearchRequest(), resultWith(available()), objectMapper);

        JsonNode sentiment = objectMapper.readTree(stored.getMetricsSnapshot())
                .path("entries").get(0).path("sentiment");
        assertThat(sentiment.path("status").asText()).isEqualTo("available");
        assertThat(sentiment.path("label").asText()).isEqualTo("POSITIVE");
        assertThat(sentiment.path("score").asDouble()).isEqualTo(0.6);
        assertThat(sentiment.path("confidence").asDouble()).isEqualTo(0.8);
        assertThat(sentiment.path("positiveCount").asInt()).isEqualTo(7);
        assertThat(sentiment.path("neutralCount").asInt()).isEqualTo(3);
        assertThat(sentiment.path("negativeCount").asInt()).isEqualTo(2);
        assertThat(sentiment.path("methodology").asText()).isNotBlank();

        assertThat(stored.getSentimentSummary()).contains("sentiment POSITIVE");
        assertThat(stored.getExecutiveSummary()).contains("sentiment POSITIVE");
    }

    @Test
    void persistsNotAnalyzedDistinctFromUnavailable() throws Exception {
        ResearchResult skipped = ResearchResultAssembler.assemble(
                new ResearchRequest(), resultWith(null), objectMapper);
        JsonNode skippedNode = objectMapper.readTree(skipped.getMetricsSnapshot())
                .path("entries").get(0).path("sentiment");
        assertThat(skippedNode.path("status").asText()).isEqualTo("not-analyzed");

        ResearchResult unavailable = ResearchResultAssembler.assemble(new ResearchRequest(),
                resultWith(SentimentResult.unavailable("AAPL", UnavailableReason.NO_ARTICLES,
                        LexiconSentimentAnalyzer.METHODOLOGY)),
                objectMapper);
        JsonNode unavailableNode = objectMapper.readTree(unavailable.getMetricsSnapshot())
                .path("entries").get(0).path("sentiment");
        assertThat(unavailableNode.path("status").asText()).isEqualTo("unavailable");
        assertThat(unavailableNode.path("reason").asText()).isEqualTo("NO_ARTICLES");
        assertThat(unavailable.getSentimentSummary()).contains("sentiment unavailable (NO_ARTICLES)");
    }
}
