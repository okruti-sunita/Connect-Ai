package com.connectai.agent.api;

import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.persistence.AgentConversation;
import java.time.Instant;
import java.util.UUID;

public record AgentConversationSummaryResponse(UUID id, String question, AgentExecutionStatus status,
        String answer, boolean grounded, boolean fallbackUsed, Instant createdAt, Instant completedAt) {
    public static AgentConversationSummaryResponse from(AgentConversation conversation) {
        return new AgentConversationSummaryResponse(conversation.getId(), conversation.getQuestion(),
                conversation.getStatus(), conversation.getAnswer(), conversation.isGrounded(),
                conversation.isFallbackUsed(), conversation.getCreatedAt(), conversation.getCompletedAt());
    }
}
