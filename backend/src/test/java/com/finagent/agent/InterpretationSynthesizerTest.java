package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.model.FactEntry;
import com.finagent.agent.prompts.AgentPrompts;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.AgentException;
import com.finagent.market.dto.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterpretationSynthesizerTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private InterpretationSynthesizer synthesizer;

    @BeforeEach
    void setUp() {
        synthesizer = new InterpretationSynthesizer(chatClient, new FinAgentProperties(),
                Executors.newCachedThreadPool());
    }

    private void stubChatChain() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    private static FactBundle bundle() {
        Quote quote = new Quote("AAPL", new BigDecimal("150.25"), BigDecimal.ZERO, BigDecimal.ZERO, Instant.now());
        FactEntry entry = new FactEntry("AAPL", quote, null, 5, null, null, 0, List.of(), null, null, List.of());
        return new FactBundle("Research AAPL", List.of("AAPL"), Instant.now(), List.of(entry));
    }

    @Test
    void synthesizesProseFromBundle() {
        stubChatChain();
        when(callResponseSpec.content()).thenReturn("  A steady educational summary.  ");

        String result = synthesizer.synthesize(bundle());

        assertThat(result).isEqualTo("A steady educational summary.");
        verify(requestSpec).system(AgentPrompts.SYSTEM_PROMPT);
    }

    @Test
    void rejectsBlankCompletion() {
        stubChatChain();
        when(callResponseSpec.content()).thenReturn("   ");

        assertThatThrownBy(() -> synthesizer.synthesize(bundle()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("no usable prose");
    }

    @Test
    void wrapsProviderFailure() {
        stubChatChain();
        when(callResponseSpec.content()).thenThrow(new RuntimeException("model overloaded"));

        assertThatThrownBy(() -> synthesizer.synthesize(bundle()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("synthesis failed");
    }

    @Test
    void rejectsMissingFacts() {
        assertThatThrownBy(() -> synthesizer.synthesize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hungModelCallFailsFastWithTimeout() {
        // Phase 17: the model never answers — the timeout (not the test) ends the call.
        stubChatChain();
        when(callResponseSpec.content()).thenAnswer(inv -> {
            Thread.sleep(30_000);
            return "too late";
        });
        FinAgentProperties props = new FinAgentProperties();
        props.getAi().setSynthesisTimeout(Duration.ofMillis(100));
        InterpretationSynthesizer impatient =
                new InterpretationSynthesizer(chatClient, props, Executors.newCachedThreadPool());

        long started = System.currentTimeMillis();
        assertThatThrownBy(() -> impatient.synthesize(bundle()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("timed out");
        // Fails in ~100ms, not 30s: no hung research job.
        assertThat(System.currentTimeMillis() - started).isLessThan(10_000);
    }
}
