package com.connectai.agent.api;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.GroundedAnswer;
import java.util.List;
import java.util.UUID;

public record AgentChatResponse(UUID id, AgentExecutionStatus status, String question, String answer,
                                boolean grounded, boolean fallbackUsed, String stopReason,
                                List<AgentChatStepResponse> steps, List<GroundedEvidenceResponse> evidence) {
    public AgentChatResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static AgentChatResponse from(UUID id, String question, AgentExecutionState execution, GroundedAnswer answer) {
        List<AgentChatStepResponse> steps = execution.steps().stream()
                .map(AgentChatStepResponse::from).toList();
        List<GroundedEvidenceResponse> evidence = answer.evidence().stream().map(GroundedEvidenceResponse::from).toList();
        return new AgentChatResponse(id, execution.status(), question, answer.answer(), answer.grounded(), answer.fallbackUsed(), execution.stopReason(), steps, evidence);
    }
}
