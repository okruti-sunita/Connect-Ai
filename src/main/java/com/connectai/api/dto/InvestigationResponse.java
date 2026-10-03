package com.connectai.api.dto;

import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;

import java.time.Instant;
import java.util.List;

public record InvestigationResponse(
        Long id,
        String question,
        InvestigationStatus status,
        String resolvedAnswer,
        Instant createdAt,
        List<EvidenceResponse> evidence
) {
    public static InvestigationResponse from(Investigation i) {
        List<EvidenceResponse> evidence = i.getEvidenceItems().stream()
                .map(EvidenceResponse::from)
                .toList();
        return new InvestigationResponse(
                i.getId(), i.getQuestion(), i.getStatus(),
                i.getResolvedAnswer(), i.getCreatedAt(), evidence);
    }
}
