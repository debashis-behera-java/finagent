package com.finagent.config;

import com.finagent.agent.prompts.AgentPrompts;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AI wiring (Phase 7).
 *
 * <p>Defines the research {@link ChatClient} from the auto-configured
 * {@link ChatClient.Builder} (which itself is backed by the OpenAI chat model when
 * {@code SPRING_AI_OPENAI_API_KEY} is set). The client carries the FinAgent system
 * prompt as its default so every synthesis call inherits the "facts in, prose out"
 * guardrails; per-call user prompts supply the FactBundle.</p>
 *
 * <p>The agent executes the finance tools in-process through the application
 * services — the same code the MCP server exposes. A self-loop HTTP call to our
 * own {@code /mcp} endpoint would add a network failure domain for zero
 * architectural gain, so no MCP client starter is on the classpath (Phase 15
 * audit: unused WebFlux client stack removed).</p>
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient researchChatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(AgentPrompts.SYSTEM_PROMPT).build();
    }

    /**
     * Phase 17: bounded pool that hosts LLM synthesis calls so a per-call
     * timeout can abandon (interrupt) a hung model invocation. Separate from
     * the research pool: synthesis timeouts must never consume research-job
     * threads while waiting. Caller-runs backpressure, no unbounded queue.
     */
    @Bean
    public Executor synthesisExecutor(FinAgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("synthesis-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public com.finagent.agent.InterpretationSynthesizer interpretationSynthesizer(
            @Qualifier("researchChatClient") ChatClient chatClient,
            FinAgentProperties properties,
            @Qualifier("synthesisExecutor") Executor synthesisExecutor) {
        return new com.finagent.agent.InterpretationSynthesizer(chatClient, properties, synthesisExecutor);
    }
}
