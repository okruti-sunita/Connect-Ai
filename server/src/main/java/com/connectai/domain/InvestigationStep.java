package com.connectai.domain;

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
import lombok.Getter;

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

    protected InvestigationStep() { }

    public InvestigationStep(int stepNumber, String toolName, AgentStepStatus status) {
        this.stepNumber = stepNumber;
        this.toolName = toolName == null || toolName.isBlank() ? "Unknown tool" : toolName;
        this.status = status;
    }

    void setInvestigation(Investigation investigation) {
        this.investigation = investigation;
    }
}
