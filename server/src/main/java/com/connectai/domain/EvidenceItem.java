package com.connectai.domain;

import com.connectai.agent.EvidenceCompleteness;
import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One piece of evidence gathered from an external tool.
 * sourceRef is the traceability anchor, e.g. "PR #456" or "JIRA-123".
 */
@Entity
@Getter
public class EvidenceItem extends AbstractUUIDPersistable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "investigation_id", nullable = false)
    private Investigation investigation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, length = 300)
    private String sourceRef;

    @Column(nullable = false, length = 4000)
    private String summary;

    private Double confidenceScore;

    @Column(nullable = false)
    private Instant fetchedAt;

    /** Provenance for evidence gathered by the dynamic agent; null for legacy demo evidence. */
    @Column(name = "step_number")
    private Integer stepNumber;

    @Column(name = "tool_name", length = 200)
    private String toolName;

    @Column(name = "server_id")
    private UUID serverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceCompleteness completeness = EvidenceCompleteness.UNKNOWN;

    protected EvidenceItem() {
        // required by JPA
    }

    public EvidenceItem(
            SourceType sourceType,
            String sourceRef,
            String summary,
            Double confidenceScore
    ) {
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.summary = summary;
        this.confidenceScore = confidenceScore;
        this.fetchedAt = Instant.now();
    }

    public EvidenceItem(
            SourceType sourceType,
            String sourceRef,
            String summary,
            int stepNumber,
            String toolName,
            UUID serverId,
            EvidenceCompleteness completeness
    ) {
        this(sourceType, sourceRef, summary, null);
        this.stepNumber = stepNumber;
        this.toolName = toolName;
        this.serverId = serverId;
        this.completeness = completeness == null ? EvidenceCompleteness.UNKNOWN : completeness;
    }

    public EvidenceItem(SourceType sourceType, String sourceRef, String summary,
                        int stepNumber, String toolName, UUID serverId) {
        this(sourceType, sourceRef, summary, stepNumber, toolName, serverId, EvidenceCompleteness.UNKNOWN);
    }

    void setInvestigation(Investigation investigation) {
        this.investigation = investigation;
    }
}
