package com.connectai.agent.persistence;

import com.connectai.agent.AgentStepStatus;
import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "agent_execution_step")
public class AgentExecutionStepRecord extends AbstractUUIDPersistable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AgentConversation conversation;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(name = "tool_name", nullable = false, length = 200)
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentStepStatus status;

    protected AgentExecutionStepRecord() { }

    AgentExecutionStepRecord(AgentConversation conversation, int stepNumber, String toolName, AgentStepStatus status) {
        this.conversation = conversation;
        this.stepNumber = stepNumber;
        this.toolName = toolName;
        this.status = status;
    }

    public int getStepNumber() { return stepNumber; }
    public String getToolName() { return toolName; }
    public AgentStepStatus getStatus() { return status; }
}
