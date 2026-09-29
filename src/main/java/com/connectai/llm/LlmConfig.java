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
                               @Value("${connectai.llm.base-url:https://api.anthropic.com}") String baseUrl,
                               @Value("${connectai.llm.model:claude-haiku-4-5-20251001}") String model,
                               @Value("${connectai.llm.max-tokens:1024}") int maxTokens,
                               @Value("${connectai.llm.timeout-seconds:30}") long timeoutSeconds,
                               ObjectMapper mapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "connectai.llm.enabled=true but no API key is set. Set the ANTHROPIC_API_KEY environment variable.");
        }
        log.info("LLM mode ON: model={}", model);
        return new AnthropicClient(baseUrl, apiKey, model, maxTokens, Duration.ofSeconds(timeoutSeconds), mapper);
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
