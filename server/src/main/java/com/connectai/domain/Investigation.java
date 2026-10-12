package com.connectai.domain;

import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One developer question / incident being investigated.
 */
@Entity
@Getter
public class Investigation extends AbstractUUIDPersistable {

    private static final int MAX_TITLE_LENGTH = 120;

    @Column(nullable = false, length = 2000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvestigationStatus status;

    @Column(length = 8000)
    private String resolvedAnswer;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean archived;

    /** Why the agent stopped: user-safe text, null while running. */
    @Column(name = "stop_reason", length = 1000)
    private String stopReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** How it was answered: FAST_PATH (deterministic recipe, no model call) or AI_AGENT. */
    @Column(length = 20)
    private String route;

    /** Why this route was taken: what the fast path recognised, or why it handed over to the AI. */
    @Column(name = "route_detail", length = 1000)
    private String routeDetail;

    @OneToMany(
            mappedBy = "investigation",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("id ASC")
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNumber ASC")
    private List<InvestigationStep> steps = new ArrayList<>();

    protected Investigation() {
        // required by JPA
    }

    public Investigation(String question) {
        this.question = question;
        this.status = InvestigationStatus.RUNNING;
        this.createdAt = Instant.now();
    }

    public void addEvidence(EvidenceItem item) {
        evidenceItems.add(item);
        item.setInvestigation(this);
    }

    public void addStep(InvestigationStep step) {
        steps.add(step);
        step.setInvestigation(this);
    }

    public void markAnswered(String answer) {
        finish(InvestigationStatus.ANSWERED, answer, null);
    }

    public void markFailed(String reason) {
        finish(InvestigationStatus.FAILED, reason, reason);
    }

    /** Moves the investigation to a terminal state. */
    public void finish(InvestigationStatus terminalStatus, String answer, String reason) {
        if (terminalStatus == null || !terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("terminal status required");
        }
        this.status = terminalStatus;
        this.resolvedAnswer = answer == null ? null : truncate(answer, 8000);
        this.stopReason = reason == null ? null : truncate(reason, 1000);
        this.completedAt = Instant.now();
    }

    public void answeredBy(String route) {
        this.route = route;
    }

    public void answeredBy(String route, String detail) {
        this.route = route;
        this.routeDetail = detail == null ? null : truncate(detail, 1000);
    }

    public boolean isRunning() {
        return status == InvestigationStatus.RUNNING;
    }

    public void replaceSteps(List<InvestigationStep> newSteps) {
        steps.clear();
        newSteps.forEach(this::addStep);
    }

    public void replaceEvidence(List<EvidenceItem> newEvidence) {
        evidenceItems.clear();
        newEvidence.forEach(this::addEvidence);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    public void archive() {
        this.archived = true;
    }

    public void unarchive() {
        this.archived = false;
    }

    /** Short single-line label for lists and headings, derived from the question. */
    public String getTitle() {
        String oneLine = question == null ? "" : question.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_TITLE_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_TITLE_LENGTH - 3) + "...";
    }
}