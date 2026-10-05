package com.connectai.api.dto;

import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;

import java.time.Instant;
import java.util.UUID;

public record InvestigationSummary(
        UUID id,
        String question,
        InvestigationStatus status,
        Instant createdAt,
        int evidenceCount
) {
    public static InvestigationSummary from(Investigation i) {
        return new InvestigationSummary(
                i.getId(), i.getQuestion(), i.getStatus(),
                i.getCreatedAt(), i.getEvidenceItems().size());
    }
}
