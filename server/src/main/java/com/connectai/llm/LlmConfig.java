package com.connectai.llm;

import com.connectai.agent.AnswerGenerator;
import com.connectai.agent.Planner;
import com.connectai.agent.RuleBasedPlanner;
import com.connectai.agent.TemplateAnswerGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Active only when connectai.llm.enabled=true. The @Primary beans win over the Step 2
 * rule-based/template beans, which stay in the context and are handed in as fallbacks.
 */
@Configuration
@ConditionalOnProperty(name = "connectai.llm.enabled", havingValue = "true")
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(@Value("${connectai.llm.api-key:}") String apiKey,
                               @Value("${connectai.llm.provider:anthropic}") String provider,
                               @Value("${connectai.llm.base-url:https://api.anthropic.com}") String baseUrl,
                               @Value("${connectai.llm.ollama-base-url:http://localhost:11434}") String ollamaBaseUrl,
                               @Value("${connectai.llm.ollama-model:qwen3:8b}") String ollamaModel,
                               @Value("${connectai.llm.model:claude-haiku-4-5-20251001}") String model,
                               @Value("${connectai.llm.max-tokens:1024}") int maxTokens,
                               @Value("${connectai.llm.timeout-seconds:30}") long timeoutSeconds,
                               @Value("${connectai.llm.ollama-num-ctx:8192}") int ollamaNumCtx,
                               @Value("${connectai.llm.temperature:0}") double temperature,
                               ObjectMapper mapper) {
        if ("ollama".equalsIgnoreCase(provider)) {
            log.info("LLM mode ON: provider=ollama, model={}, baseUrl={}, numCtx={}, maxTokens={}, timeoutSeconds={}",
                    ollamaModel, ollamaBaseUrl, ollamaNumCtx, maxTokens, timeoutSeconds);
            OllamaClient client = new OllamaClient(ollamaBaseUrl, ollamaModel, Duration.ofSeconds(timeoutSeconds),
                    mapper, ollamaNumCtx, maxTokens, temperature);
            reportModelAvailability(client);
            return client;
        }
        if (!"anthropic".equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported connectai.llm.provider='" + provider
                    + "'. Supported providers are 'anthropic' and 'ollama'.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "connectai.llm.enabled=true but no API key is set. Set the ANTHROPIC_API_KEY environment variable.");
        }
        log.info("LLM mode ON: provider=anthropic, model={}", model);
        return new AnthropicClient(baseUrl, apiKey, model, maxTokens, Duration.ofSeconds(timeoutSeconds), mapper);
    }

    /**
     * Startup diagnostic only: a missing model or stopped Ollama is reported loudly but does not
     * prevent startup, so connections can still be configured. Investigations then fail with the
     * same actionable message, and /api/health reports it.
     */
    /**
     * Loads the model into memory in the background so the first investigation does not pay the
     * several-second load time. Purely an optimisation: failures are logged and ignored.
     */
    private static void warmUp(OllamaClient client) {
        Thread warmUp = new Thread(() -> {
            long started = System.nanoTime();
            try {
                client.complete("Reply with OK.", "OK");
                log.info("Ollama model '{}' warmed up in {} ms", client.model(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("Ollama warm-up failed (the first request will be slower): {}", e.getMessage());
            }
        }, "ollama-warmup");
        warmUp.setDaemon(true);
        warmUp.start();
    }

    private static void reportModelAvailability(OllamaClient client) {
        try {
            if (OllamaClient.isInstalled(client.model(), client.installedModels())) {
                log.info("Ollama model '{}' is installed and reachable at {}", client.model(), client.baseUrl());
                warmUp(client);
            } else {
                log.error("Ollama model '{}' is NOT installed at {}. Run: ollama pull {}",
                        client.model(), client.baseUrl(), client.model());
            }
        } catch (LlmException e) {
            log.error("Ollama readiness check failed: {}", e.getMessage());
        }
    }

    @Bean
    @Primary
    public Planner llmPlanner(LlmClient llm, RuleBasedPlanner fallback) {
        return new LlmPlanner(llm, fallback);
    }

    @Bean
    @Primary
    public AnswerGenerator llmAnswerGenerator(LlmClient llm, TemplateAnswerGenerator fallback) {
        return new LlmAnswerGenerator(llm, fallback);
    }
}
