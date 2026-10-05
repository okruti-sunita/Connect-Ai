package com.connectai.domain;

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

    void setInvestigation(Investigation investigation) {
        this.investigation = investigation;
    }
}