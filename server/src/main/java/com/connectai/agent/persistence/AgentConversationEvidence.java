package com.connectai.agent.persistence;

import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "agent_conversation_evidence")
public class AgentConversationEvidence extends AbstractUUIDPersistable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AgentConversation conversation;

    @Column(nullable = false, length = 20)
    private String citation;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(name = "server_id")
    private UUID serverId;

    @Column(name = "tool_name", nullable = false, length = 200)
    private String toolName;

    @Column(nullable = false, length = 4000)
    private String content;

    protected AgentConversationEvidence() { }

    AgentConversationEvidence(AgentConversation conversation, String citation, int stepNumber,
                              UUID serverId, String toolName, String content) {
        this.conversation = conversation;
        this.citation = citation;
        this.stepNumber = stepNumber;
        this.serverId = serverId;
        this.toolName = toolName;
        this.content = content == null ? "" : content.substring(0, Math.min(content.length(), 4000));
    }

    public String getCitation() { return citation; }
    public int getStepNumber() { return stepNumber; }
    public UUID getServerId() { return serverId; }
    public String getToolName() { return toolName; }
    public String getContent() { return content; }
}
