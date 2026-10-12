package com.connectai.agent.api;

import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.StepAudit;
import com.connectai.agent.persistence.AgentExecutionStepRecord;

import java.util.UUID;

public record AgentChatStepResponse(int stepNumber, String toolName, AgentStepStatus status,
                                    UUID serverId, String argumentsJson, String reason,
                                    String errorMessage, Long durationMs) {

    public static AgentChatStepResponse from(AgentExecutionStep step) {
        return new AgentChatStepResponse(step.stepNumber(), step.plan().toolName(), step.status(),
                step.plan().serverId(), StepAudit.argumentsJson(step.plan().arguments()),
                step.plan().reason(), step.errorMessage(), step.durationMs());
    }

    public static AgentChatStepResponse from(AgentExecutionStepRecord step) {
        return new AgentChatStepResponse(step.getStepNumber(), step.getToolName(), step.getStatus(),
                step.getServerId(), step.getArgumentsJson(), step.getReason(),
                step.getErrorMessage(), step.getDurationMs());
    }
}
