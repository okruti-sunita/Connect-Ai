package com.connectai.agent.persistence;

import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "agent_conversation")
public class AgentConversation extends AbstractUUIDPersistable {

    @Column(nullable = false, length = 2000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentExecutionStatus status;

    @Column(length = 8000)
    private String answer;

    @Column(nullable = false)
    private boolean grounded;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepNumber ASC")
    private List<AgentExecutionStepRecord> steps = new ArrayList<>();

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("stepNumber ASC, citation ASC")
    private List<AgentConversationEvidence> evidence = new ArrayList<>();

    protected AgentConversation() {
        // Required by JPA.
    }

    public AgentConversation(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (question.length() > 2000) {
            throw new IllegalArgumentException("question must not exceed 2000 characters");
        }
        this.question = question.trim();
        this.status = AgentExecutionStatus.RUNNING;
        this.createdAt = Instant.now();
    }

    public void complete(AgentExecutionState execution, GroundedAnswer groundedAnswer) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(groundedAnswer, "groundedAnswer");
        if (!Objects.equals(question, execution.question())) {
            throw new IllegalArgumentException("execution question does not match conversation question");
        }
        this.status = execution.status();
        this.answer = groundedAnswer.answer();
        this.grounded = groundedAnswer.grounded();
        this.fallbackUsed = groundedAnswer.fallbackUsed();
        this.completedAt = Instant.now();
        this.steps.clear();
        this.evidence.clear();
        execution.steps().forEach(step -> this.steps.add(new AgentExecutionStepRecord(
                this, step.stepNumber(), step.plan().toolName(), step.status())));
        groundedAnswer.evidence().forEach(item -> this.evidence.add(new AgentConversationEvidence(
                this, item.citation(), item.stepNumber(), item.serverId(), item.toolName(), item.content())));
    }

    public void markFailed() {
        this.status = AgentExecutionStatus.FAILED;
        this.answer = "The request could not be completed.";
        this.grounded = false;
        this.fallbackUsed = true;
        this.completedAt = Instant.now();
    }

    public String getQuestion() { return question; }
    public AgentExecutionStatus getStatus() { return status; }
    public String getAnswer() { return answer; }
    public boolean isGrounded() { return grounded; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public List<AgentExecutionStepRecord> getSteps() { return List.copyOf(steps); }
    public List<AgentConversationEvidence> getEvidence() { return List.copyOf(evidence); }
}
