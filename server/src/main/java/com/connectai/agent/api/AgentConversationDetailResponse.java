package com.connectai.agent.api;

import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.persistence.AgentConversation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentConversationDetailResponse(UUID id, String question, AgentExecutionStatus status,
        String answer, boolean grounded, boolean fallbackUsed, Instant createdAt, Instant completedAt,
        List<AgentChatStepResponse> steps, List<GroundedEvidenceResponse> evidence) {
    public AgentConversationDetailResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static AgentConversationDetailResponse from(AgentConversation conversation) {
        List<AgentChatStepResponse> steps = conversation.getSteps().stream()
                .map(step -> new AgentChatStepResponse(step.getStepNumber(), step.getToolName(), step.getStatus())).toList();
        List<GroundedEvidenceResponse> evidence = conversation.getEvidence().stream()
                .map(item -> new GroundedEvidenceResponse(item.getCitation(), item.getStepNumber(),
                        item.getServerId(), item.getToolName(), item.getContent())).toList();
        return new AgentConversationDetailResponse(conversation.getId(), conversation.getQuestion(),
                conversation.getStatus(), conversation.getAnswer(), conversation.isGrounded(),
                conversation.isFallbackUsed(), conversation.getCreatedAt(), conversation.getCompletedAt(), steps, evidence);
    }
}
