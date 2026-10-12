package com.connectai.domain;

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
import lombok.Getter;

import java.util.UUID;

@Entity
@Getter
@Table(name = "investigation_step")
public class InvestigationStep extends AbstractUUIDPersistable {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Column(name = "tool_name", nullable = false, length = 200)
    private String toolName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentStepStatus status;

    @Column(name = "server_id")
    private UUID serverId;

    /** Redacted canonical JSON of the validated arguments actually sent to the tool. */
    @Column(name = "arguments_json", length = 4000)
    private String argumentsJson;

    /** The planner's one-sentence operational reason (not a reasoning trace). */
    @Column(length = 500)
    private String reason;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected InvestigationStep() { }

    public InvestigationStep(int stepNumber, String toolName, AgentStepStatus status) {
        this.stepNumber = stepNumber;
        this.toolName = toolName == null || toolName.isBlank() ? "Unknown tool" : toolName;
        this.status = status;
    }

    public static InvestigationStep from(AgentExecutionStep step) {
        InvestigationStep record = new InvestigationStep(step.stepNumber(), step.plan().toolName(), step.status());
        record.serverId = step.plan().serverId();
        record.argumentsJson = StepAudit.argumentsJson(step.plan().arguments());
        record.reason = StepAudit.truncate(step.plan().reason(), 500);
        record.errorMessage = StepAudit.truncate(step.errorMessage(), 1000);
        record.durationMs = step.durationMs();
        return record;
    }

    void setInvestigation(Investigation investigation) {
        this.investigation = investigation;
    }
}
