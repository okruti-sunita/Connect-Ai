package com.connectai.config;

import com.connectai.agent.GroundedAnswerGenerator;
import com.connectai.agent.TemplateGroundedAnswerGenerator;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.llm.GroundingValidator;
import com.connectai.llm.LlmClient;
import com.connectai.llm.LlmGroundedAnswerGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Enables the LLM-backed grounded final answer when LLM mode is enabled. */
@Configuration
@ConditionalOnProperty(name = "connectai.llm.enabled", havingValue = "true")
public class GroundedAnswerLlmConfig {

    @Bean
    @Primary
    public GroundedAnswerGenerator llmGroundedAnswerGenerator(
            LlmClient llm,
            GroundedAnswerContextBuilder contextBuilder,
            GroundingValidator validator,
            TemplateGroundedAnswerGenerator fallback) {
        return new LlmGroundedAnswerGenerator(
                llm,
                contextBuilder,
                validator,
                fallback);
    }
}
