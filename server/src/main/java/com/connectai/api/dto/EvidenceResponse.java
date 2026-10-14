package com.connectai.api.dto;

import com.connectai.agent.EvidenceCompleteness;
import com.connectai.domain.EvidenceItem;
import com.connectai.domain.SourceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One evidence record. For agent evidence, {@code sourceRef} is the citation id used in the
 * answer (E1, E2...), and stepNumber/toolName/serverId identify the tool call that produced it.
 * {@code confidenceScore} is only set by the legacy demo tools; agent evidence leaves it null.
 */
public record EvidenceResponse(
        UUID id,
        SourceType sourceType,
        String sourceRef,
        String summary,
        Double confidenceScore,
        Instant fetchedAt,
        Integer stepNumber,
        String toolName,
        UUID serverId,
        EvidenceCompleteness completeness,
        /** Connection label; null for demo evidence or when the connection was removed since. */
        String serverName
) {
    public static EvidenceResponse from(EvidenceItem e) {
        return from(e, Map.of());
    }

    public static EvidenceResponse from(EvidenceItem e, Map<UUID, String> serverNames) {
        return new EvidenceResponse(
                e.getId(), e.getSourceType(), e.getSourceRef(),
                e.getSummary(), e.getConfidenceScore(), e.getFetchedAt(),
                e.getStepNumber(), e.getToolName(), e.getServerId(), e.getCompleteness(),
                e.getServerId() == null ? null : serverNames.get(e.getServerId()));
    }
}
