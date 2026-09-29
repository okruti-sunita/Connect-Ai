package com.connectai.api.dto;

import com.connectai.domain.EvidenceItem;
import com.connectai.domain.SourceType;

import java.time.Instant;

public record EvidenceResponse(
        Long id,
        SourceType sourceType,
        String sourceRef,
        String summary,
        Double confidenceScore,
        Instant fetchedAt
) {
    public static EvidenceResponse from(EvidenceItem e) {
        return new EvidenceResponse(
                e.getId(), e.getSourceType(), e.getSourceRef(),
                e.getSummary(), e.getConfidenceScore(), e.getFetchedAt());
    }
}
