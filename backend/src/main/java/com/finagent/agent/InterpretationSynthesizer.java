package com.finagent.agent;

import com.finagent.agent.model.FactBundle;
import com.finagent.agent.prompts.AgentPrompts;
import com.finagent.config.FinAgentProperties;
import com.finagent.exception.AgentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM synthesis step: FactBundle + computed metrics in, interpretation prose out (Phase 7).
 *
 * <p>The model <b>never sources numbers</b> — all figures live in the bundle already. The
 * prompt explicitly forbids inventing figures, and the output is verified by
 * {@link SafetyGuard} before it leaves the agent. A blank or failed completion is an
 * {@link AgentException}, never an empty success.</p>
 *
 * <p>Phase 17: the model call runs under a hard timeout on a dedicated bounded
 * pool. A hung provider fails the job fast ({@code SYNTHESIS_FAILED} via the
 * runner mapping) instead of occupying a research thread indefinitely. No
 * retries here — a timeout is a definitive failure for this run; the user can
 * resubmit. Wired explicitly in {@code AiConfig} (not component-scanned) so
 * the executor binding stays obvious.</p>
 */
@Slf4j
public class InterpretationSynthesizer {

    private final ChatClient chatClient;
    private final Duration timeout;
    private final Executor synthesisExecutor;

    public InterpretationSynthesizer(ChatClient chatClient, FinAgentProperties properties,
                                     Executor synthesisExecutor) {
        this.chatClient = chatClient;
        this.timeout = properties.getAi().getSynthesisTimeout();
        this.synthesisExecutor = synthesisExecutor;
    }

    /** Synthesize guarded interpretation prose for a validated FactBundle. */
    public String synthesize(FactBundle bundle) {
        if (bundle == null || bundle.entries().isEmpty()) {
            throw new IllegalArgumentException("Cannot synthesize interpretation without facts");
        }
        String userPrompt = AgentPrompts.interpretationPrompt(bundle.toPromptText());
        CompletableFuture<String> call = CompletableFuture.supplyAsync(
                () -> chatClient.prompt()
                        .system(AgentPrompts.SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content(),
                synthesisExecutor);
        String content;
        try {
            content = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            // Abandon AND interrupt the hung provider call so the pool thread is
            // reclaimed promptly; the job fails safe (SYNTHESIS_FAILED downstream).
            call.cancel(true);
            log.warn("Interpretation synthesis timed out after {} for tickers {}",
                    timeout, bundle.tickers());
            throw new AgentException(
                    "Interpretation synthesis timed out; the model did not respond in time", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw new AgentException("Interpretation synthesis was interrupted", ex);
        } catch (ExecutionException ex) {
            // Provider/model details stay server-side: the client and the persisted
            // FAILED row receive a generic code, never endpoint/key/model internals.
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.warn("Interpretation synthesis failed for tickers {}: {}", bundle.tickers(),
                    cause.getMessage());
            throw new AgentException("Interpretation synthesis failed (model error; details logged)", ex);
        }
        if (content == null || content.isBlank()) {
            throw new AgentException("Interpretation synthesis returned no usable prose");
        }
        log.debug("Synthesized interpretation ({} chars) for tickers {}", content.length(), bundle.tickers());
        return content.strip();
    }
}
