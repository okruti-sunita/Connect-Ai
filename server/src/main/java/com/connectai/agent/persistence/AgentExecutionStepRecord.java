package com.connectai.agent.persistence;

import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.StepAudit;
import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

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

    @Column(name = "server_id")
    private UUID serverId;

    @Column(name = "arguments_json", length = 4000)
    private String argumentsJson;

    @Column(length = 500)
    private String reason;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected AgentExecutionStepRecord() { }

    AgentExecutionStepRecord(AgentConversation conversation, AgentExecutionStep step) {
        this.conversation = conversation;
        this.stepNumber = step.stepNumber();
        this.toolName = step.plan().toolName();
        this.status = step.status();
        this.serverId = step.plan().serverId();
        this.argumentsJson = StepAudit.argumentsJson(step.plan().arguments());
        this.reason = StepAudit.truncate(step.plan().reason(), 500);
        this.errorMessage = StepAudit.truncate(step.errorMessage(), 1000);
        this.durationMs = step.durationMs();
    }

    public UUID getServerId() { return serverId; }
    public String getArgumentsJson() { return argumentsJson; }
    public String getReason() { return reason; }
    public String getErrorMessage() { return errorMessage; }
    public Long getDurationMs() { return durationMs; }

    public int getStepNumber() { return stepNumber; }
    public String getToolName() { return toolName; }
    public AgentStepStatus getStatus() { return status; }
}
