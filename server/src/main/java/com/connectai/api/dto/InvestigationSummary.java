package com.connectai.api.dto;

import com.connectai.domain.EvidenceItem;
import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.SourceType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the Investigations list screen. */
public record InvestigationSummary(
        UUID id,
        String title,
        String question,
        InvestigationStatus status,
        boolean archived,
        List<SourceType> sources,
        int evidenceCount,
        Instant createdAt,
        String route
) {
    public static InvestigationSummary from(Investigation i) {
        List<SourceType> sources = i.getEvidenceItems().stream()
                .map(EvidenceItem::getSourceType)
                .distinct()
                .sorted()
                .toList();

        return new InvestigationSummary(
                i.getId(),
                i.getTitle(),
                i.getQuestion(),
                i.getStatus(),
                i.isArchived(),
                sources,
                i.getEvidenceItems().size(),
                i.getCreatedAt(),
                i.getRoute());
    }
}
