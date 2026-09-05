package com.finagent.agent;

import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.news.sentiment.SentimentLabel;
import com.finagent.news.sentiment.UnavailableReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sentiment policy + grounding through the real agent stack (stub providers,
 * mocked {@link ChatClient}, no network/LLM). Verifies the three spec scenarios:
 * price question skips sentiment, sentiment ask includes it, comprehensive
 * analysis includes it — plus provider-failure grounding.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentSentimentTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @MockBean
    private ChatClient chatClient;

    @Autowired
    private ResearchAgent agent;

    @BeforeEach
    void stubChatClient() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(
                "Coverage looks mixed with both constructive and cautious notes. "
                + "This is educational research, not financial advice.");
    }

    @Test
    void priceQuestionSkipsSentiment() {
        AgentResult result = agent.run(
                new AgentContext("What is the current price of AAPL?", List.of("AAPL"), FROM, TO, null));

        assertThat(result.factBundle().entries().get(0).newsSentiment()).isNull();
        assertThat(result.factBundle().toPromptText()).contains("not analyzed for this run");
        assertThat(result.guardResult().passed()).isTrue();
    }

    @Test
    void sentimentAskIncludesGroundedSentiment() {
        AgentResult result = agent.run(
                new AgentContext("Analyze recent sentiment around AAPL.", List.of("AAPL"), FROM, TO, null));

        var sentiment = result.factBundle().entries().get(0).newsSentiment();
        assertThat(sentiment).isNotNull();
        assertThat(sentiment.isAvailable()).isTrue();
        assertThat(sentiment.analyzedCount()).isPositive();
        assertThat(sentiment.positiveCount() + sentiment.neutralCount() + sentiment.negativeCount())
                .isEqualTo(sentiment.analyzedCount());
        assertThat(sentiment.score()).isBetween(-1.0, 1.0);
        // Grounded: score and counts are part of the guard's number set.
        assertThat(result.factBundle().groundedNumbers())
                .contains(java.math.BigDecimal.valueOf(sentiment.score()));
        assertThat(result.factBundle().toPromptText()).contains("News sentiment: ");
        assertThat(result.guardResult().passed()).isTrue();
    }

    @Test
    void comprehensiveAnalysisIncludesSentiment() {
        AgentResult result = agent.run(
                new AgentContext("Give me a comprehensive analysis of AAPL.", List.of("AAPL"), FROM, TO, null));

        assertThat(result.factBundle().entries().get(0).newsSentiment()).isNotNull();
    }

    @Test
    void providerFailureGroundsUnavailableSentiment() {
        AgentResult result = agent.run(
                new AgentContext("Analyze sentiment for both tickers.", List.of("AAPL", "FAIL"), FROM, TO, null));

        var failed = result.factBundle().entries().stream()
                .filter(e -> e.symbol().equals("FAIL"))
                .findFirst().orElseThrow();
        assertThat(failed.newsSentiment()).isNotNull();
        assertThat(failed.newsSentiment().label()).isEqualTo(SentimentLabel.UNAVAILABLE);
        assertThat(failed.newsSentiment().unavailableReason()).isEqualTo(UnavailableReason.PROVIDER_ERROR);
        assertThat(result.factBundle().toPromptText()).contains("unavailable (PROVIDER_ERROR)");
    }

    @Test
    void policyGateMatchesSpecExamples() {
        assertThat(ResearchAgent.wantsSentiment("What is the current price of TCS?")).isFalse();
        assertThat(ResearchAgent.wantsSentiment("Analyze recent sentiment around TCS.")).isTrue();
        assertThat(ResearchAgent.wantsSentiment("Give me a comprehensive analysis of TCS.")).isTrue();
        assertThat(ResearchAgent.wantsSentiment(null)).isFalse();
    }
}
