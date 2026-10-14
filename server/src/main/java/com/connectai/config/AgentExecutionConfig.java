package com.connectai.config;

import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.DynamicToolPlanner;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.RuleBasedDynamicToolPlanner;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolSemanticRetriever;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.fastpath.FastPathEngine;
import com.connectai.llm.LlmMcpToolPlanner;
import com.connectai.llm.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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
            LlmClient llmClient,
            ToolSemanticRetriever semanticRetriever,
            @Value("${connectai.agent.planner.shortlist-size:6}") int shortlistSize,
            @Value("${connectai.agent.planner.max-attempts:3}") int maxAttempts) {

        return new LlmMcpToolPlanner(llmClient, shortlistSize, maxAttempts, semanticRetriever);
    }

    @Bean
    @ConditionalOnProperty(
            name = "connectai.llm.enabled",
            havingValue = "false",
            matchIfMissing = true)
    public DynamicToolPlanner ruleBasedDynamicToolPlanner(ToolSemanticRetriever semanticRetriever) {

        return new RuleBasedDynamicToolPlanner(semanticRetriever);
    }

    /** Deterministic answers for recognised questions (no model call). Disable to always use the AI agent. */
    @Bean
    @ConditionalOnProperty(name = "connectai.agent.fast-path.enabled", havingValue = "true", matchIfMissing = true)
    public FastPathEngine fastPathEngine(PlannedToolExecutor executor, GroundedAnswerContextBuilder contextBuilder) {
        return new FastPathEngine(executor, contextBuilder);
    }

    /** Cross-tool issue analysis (tracker issue -> suspect code changes + time to resolve), no model call. */
    @Bean
    @ConditionalOnProperty(name = "connectai.agent.issue-analysis.enabled", havingValue = "true", matchIfMissing = true)
    public com.connectai.agent.analysis.IssueAnalysisEngine issueAnalysisEngine(
            PlannedToolExecutor executor, GroundedAnswerContextBuilder contextBuilder) {
        return new com.connectai.agent.analysis.IssueAnalysisEngine(executor, contextBuilder);
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
            @Value("${connectai.agent.max-steps:6}") int maxSteps,
            @Value("${connectai.agent.max-duration-seconds:600}") long maxDurationSeconds,
            @Value("${connectai.agent.max-consecutive-failures:2}") int maxConsecutiveFailures) {

        return new ControlledAgentExecutor(
                planner,
                plannedToolExecutor,
                maxSteps,
                Duration.ofSeconds(maxDurationSeconds),
                maxConsecutiveFailures);
    }
}
