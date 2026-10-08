package com.connectai.config;

import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.DynamicToolPlanner;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.RuleBasedDynamicToolPlanner;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.llm.LlmMcpToolPlanner;
import com.connectai.llm.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the dynamic agent execution pipeline.
 *
 * <p>The planner implementation is selected explicitly from the LLM feature
 * flag:
 *
 * <ul>
 *     <li>LLM enabled  -> LlmMcpToolPlanner</li>
 *     <li>LLM disabled -> RuleBasedDynamicToolPlanner</li>
 * </ul>
 *
 * <p>The remaining execution pipeline is independent of the planner
 * implementation.
 */
@Configuration
public class AgentExecutionConfig {

    @Bean
    @ConditionalOnProperty(
            name = "connectai.llm.enabled",
            havingValue = "true")
    public DynamicToolPlanner llmDynamicToolPlanner(
            LlmClient llmClient) {

        return new LlmMcpToolPlanner(llmClient);
    }

    @Bean
    @ConditionalOnProperty(
            name = "connectai.llm.enabled",
            havingValue = "false",
            matchIfMissing = true)
    public DynamicToolPlanner ruleBasedDynamicToolPlanner() {

        return new RuleBasedDynamicToolPlanner();
    }

    @Bean
    public PlannedToolExecutor plannedToolExecutor(
            ToolExecutionPort toolExecutionPort) {

        return new PlannedToolExecutor(toolExecutionPort);
    }

    @Bean
    public ControlledAgentExecutor controlledAgentExecutor(
            DynamicToolPlanner planner,
            PlannedToolExecutor plannedToolExecutor,
            @Value("${connectai.agent.max-steps:6}") int maxSteps) {

        return new ControlledAgentExecutor(
                planner,
                plannedToolExecutor,
                maxSteps);
    }
}