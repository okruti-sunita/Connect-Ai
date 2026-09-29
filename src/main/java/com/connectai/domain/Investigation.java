package com.connectai.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One developer question / incident being investigated. */
@Entity
public class Investigation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 2000)
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvestigationStatus status;

    @Column(length = 8000)
    private String resolvedAnswer;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "investigation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

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

    public void markAnswered(String answer) {
        this.resolvedAnswer = answer;
        this.status = InvestigationStatus.ANSWERED;
    }

    public void markFailed(String reason) {
        this.resolvedAnswer = reason;
        this.status = InvestigationStatus.FAILED;
    }

    public Long getId() { return id; }
    public String getQuestion() { return question; }
    public InvestigationStatus getStatus() { return status; }
    public String getResolvedAnswer() { return resolvedAnswer; }
    public Instant getCreatedAt() { return createdAt; }
    public List<EvidenceItem> getEvidenceItems() { return evidenceItems; }
}
