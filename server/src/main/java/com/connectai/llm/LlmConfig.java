
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

@Configuration
@ConditionalOnProperty(name = "connectai.llm.enabled", havingValue = "true")
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(
            @Value("${connectai.llm.api-key:}") String apiKey,
            @Value("${connectai.llm.provider:anthropic}") String provider,
            @Value("${connectai.llm.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${connectai.llm.ollama-base-url:http://localhost:11434}") String ollamaBaseUrl,
            @Value("${connectai.llm.ollama-model:qwen3:8b}") String ollamaModel,
            @Value("${connectai.llm.groq-api-key:}") String groqApiKey,
            @Value("${connectai.llm.groq-base-url:https://api.groq.com/openai/v1}") String groqBaseUrl,
            @Value("${connectai.llm.groq-model:openai/gpt-oss-120b}") String groqModel,
            @Value("${connectai.llm.model:claude-haiku-4-5-20251001}") String model,
            @Value("${connectai.llm.max-tokens:1024}") int maxTokens,
            @Value("${connectai.llm.timeout-seconds:90}") long timeoutSeconds,
            @Value("${connectai.llm.ollama-num-ctx:8192}") int ollamaNumCtx,
            @Value("${connectai.llm.temperature:0}") double temperature,
            ObjectMapper mapper) {

        if ("groq".equalsIgnoreCase(provider)) {
            if (groqApiKey == null || groqApiKey.isBlank()) {
                throw new IllegalStateException(
                        "Groq API key is missing. Set the GROQ_API_KEY environment variable.");
            }

            log.info("LLM mode ON: provider=groq, model={}, baseUrl={}",
                    groqModel, groqBaseUrl);

            return new GroqClient(
                    groqBaseUrl,
                    groqApiKey,
                    groqModel,
                    maxTokens,
                    Duration.ofSeconds(timeoutSeconds),
                    temperature,
                    mapper);
        }

        if ("ollama".equalsIgnoreCase(provider)) {
            log.info(
                    "LLM mode ON: provider=ollama, model={}, baseUrl={}, numCtx={}, maxTokens={}, timeoutSeconds={}",
                    ollamaModel, ollamaBaseUrl, ollamaNumCtx, maxTokens, timeoutSeconds);

            OllamaClient client = new OllamaClient(
                    ollamaBaseUrl,
                    ollamaModel,
                    Duration.ofSeconds(timeoutSeconds),
                    mapper,
                    ollamaNumCtx,
                    maxTokens,
                    temperature);

            reportModelAvailability(client);
            return client;
        }

        if (!"anthropic".equalsIgnoreCase(provider)) {
            throw new IllegalStateException(
                    "Unsupported connectai.llm.provider='" + provider
                            + "'. Supported providers are 'anthropic', 'ollama', and 'groq'.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "connectai.llm.enabled=true but no API key is set. "
                            + "Set the ANTHROPIC_API_KEY environment variable.");
        }

        log.info("LLM mode ON: provider=anthropic, model={}", model);

        return new AnthropicClient(
                baseUrl,
                apiKey,
                model,
                maxTokens,
                Duration.ofSeconds(timeoutSeconds),
                mapper);
    }

    /**
     * Startup diagnostic for Ollama. A missing model or stopped Ollama is
     * reported without preventing the application from starting.
     */
    private static void reportModelAvailability(OllamaClient client) {
        try {
            if (OllamaClient.isInstalled(client.model(), client.installedModels())) {
                log.info("Ollama model '{}' is installed and reachable at {}",
                        client.model(), client.baseUrl());
                warmUp(client);
            } else {
                log.error("Ollama model '{}' is NOT installed at {}. Run: ollama pull {}",
                        client.model(), client.baseUrl(), client.model());
            }
        } catch (LlmException e) {
            log.error("Ollama readiness check failed: {}", e.getMessage());
        }
    }

    /**
     * Load the Ollama model in the background so the first investigation
     * does not incur the full model-loading delay.
     */
    private static void warmUp(OllamaClient client) {
        Thread warmUp = new Thread(() -> {
            long started = System.nanoTime();

            try {
                client.complete("Reply with OK.", "OK");
                log.info("Ollama model '{}' warmed up in {} ms",
                        client.model(), (System.nanoTime() - started) / 1_000_000);
            } catch (RuntimeException e) {
                log.warn("Ollama warm-up failed (the first request may be slower): {}",
                        e.getMessage());
            }
        }, "ollama-warmup");

        warmUp.setDaemon(true);
        warmUp.start();
    }

    @Bean
    @Primary
    public Planner llmPlanner(LlmClient llm, RuleBasedPlanner fallback) {
        return new LlmPlanner(llm, fallback);
    }

    @Bean
    @Primary
    public AnswerGenerator llmAnswerGenerator(
            LlmClient llm,
            TemplateAnswerGenerator fallback) {
        return new LlmAnswerGenerator(llm, fallback);
    }
}
