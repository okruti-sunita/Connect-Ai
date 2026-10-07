package com.connectai.config;

import com.connectai.agent.ControlledAgentExecutor;
import com.connectai.agent.DynamicToolPlanner;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolExecutionPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the dynamic agent execution pipeline. */
@Configuration
public class AgentExecutionConfig {

    @Bean
    public PlannedToolExecutor plannedToolExecutor(
            ToolExecutionPort toolExecutionPort) {
        return new PlannedToolExecutor(toolExecutionPort);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(DynamicToolPlanner.class)
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
