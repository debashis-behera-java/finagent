package com.finagent.agent;

import com.finagent.agent.model.AgentContext;
import com.finagent.agent.model.AgentResult;
import com.finagent.exception.FactBundleIncompleteException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end agent verification with the real stub-backed services (no PostgreSQL,
 * Docker, internet, or model API key) and a mocked {@link ChatClient} standing in
 * for the LLM. Covers plan → execute → bundle → synthesize → guard, the degraded
 * single-ticker-failure path, and the total-failure path.
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentTest {

    private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
    private static final LocalDate TO = LocalDate.of(2026, 8, 31);

    @MockBean
    private ChatClient chatClient;

    @Autowired
    private ResearchAgent agent;

    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;

    @BeforeEach
    void stubChatClient() {
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    private void llmSays(String prose) {
        when(callResponseSpec.content()).thenReturn(prose);
    }

    @Test
    void runsFullResearchLoop() {
        llmSays("A steady stock with moderate risk. This is educational research, not financial advice.");

        AgentResult result = agent.run(new AgentContext("Research AAPL", List.of("AAPL"), FROM, TO, null));

        assertThat(result.factBundle().tickers()).containsExactly("AAPL");
        assertThat(result.factBundle().entries()).hasSize(1);
        assertThat(result.factBundle().entries().get(0).quote()).isNotNull();
        assertThat(result.factBundle().entries().get(0).riskAnalysis()).isNotNull();
        assertThat(result.factBundle().entries().get(0).dataGaps()).isEmpty();
        assertThat(result.guardResult().passed()).isTrue();
        assertThat(result.interpretation()).contains("moderate risk");
        assertThat(result.completedAt()).isNotNull();
    }

    @Test
    void degradesSingleTickerFailureIntoGaps() {
        llmSays("AAPL is covered but one ticker has no data. This is educational research, not financial advice.");

        AgentResult result = agent.run(new AgentContext("Research both", List.of("AAPL", "FAIL"), FROM, TO, null));

        assertThat(result.factBundle().entries()).hasSize(2);
        assertThat(result.factBundle().entries().get(1).isEmpty()).isTrue();
        assertThat(result.factBundle().entries().get(1).dataGaps()).isNotEmpty();
        assertThat(result.guardResult().passed()).isTrue();
    }

    @Test
    void redactsUngroundedFiguresFromInterpretation() {
        llmSays("A buy signal with price target 999.99. This is educational research, not financial advice.");

        AgentResult result = agent.run(new AgentContext("Research AAPL", List.of("AAPL"), FROM, TO, null));

        assertThat(result.guardResult().passed()).isFalse();
        assertThat(result.interpretation())
                .contains(SafetyGuard.REDACTION_MARKER)
                .doesNotContain("999.99");
    }

    @Test
    void failsWhenAllSourcesFail() {
        llmSays("unreachable");

        assertThatThrownBy(() -> agent.run(new AgentContext("Research it", List.of("FAIL"), FROM, TO, null)))
                .isInstanceOf(FactBundleIncompleteException.class)
                .hasMessageContaining("FAIL");
    }
}
