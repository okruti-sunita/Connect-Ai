package com.connectai.api.dto;

import com.connectai.agent.AgentStepStatus;
import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.InvestigationStep;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full investigation record. {@code resolvedAnswer} is the grounded answer for ANSWERED/PARTIAL,
 * the honest no-evidence explanation for INCONCLUSIVE, and the failure explanation for FAILED.
 * {@code stopReason} says why the agent stopped; both are null while RUNNING.
 */
public record InvestigationResponse(
        UUID id,
        String title,
        String question,
        InvestigationStatus status,
        boolean archived,
        String resolvedAnswer,
        String stopReason,
        /** FAST_PATH or AI_AGENT; null while running. */
        String route,
        /** Why this route was taken (what the fast path recognised, or why the AI planned it). */
        String routeDetail,
        Instant createdAt,
        Instant completedAt,
        List<EvidenceResponse> evidence,
        List<StepResponse> steps
) {
    public record StepResponse(
            int stepNumber,
            String toolName,
            AgentStepStatus status,
            UUID serverId,
            /** Connection label; null when that connection was removed since. */
            String serverName,
            String argumentsJson,
            String reason,
            String errorMessage,
            Long durationMs) {

        static StepResponse from(InvestigationStep step, Map<UUID, String> serverNames) {
            return new StepResponse(
                    step.getStepNumber(),
                    step.getToolName(),
                    step.getStatus(),
                    step.getServerId(),
                    step.getServerId() == null ? null : serverNames.get(step.getServerId()),
                    step.getArgumentsJson(),
                    step.getReason(),
                    step.getErrorMessage(),
                    step.getDurationMs());
        }
    }

    public static InvestigationResponse from(Investigation i) {
        return from(i, Map.of());
    }

    /** {@code serverNames} maps connection ids to their current labels. */
    public static InvestigationResponse from(Investigation i, Map<UUID, String> serverNames) {
        List<EvidenceResponse> evidence = i.getEvidenceItems().stream()
                .map(e -> EvidenceResponse.from(e, serverNames))
                .toList();

        return new InvestigationResponse(
                i.getId(),
                i.getTitle(),
                i.getQuestion(),
                i.getStatus(),
                i.isArchived(),
                i.getResolvedAnswer(),
                i.getStopReason(),
                i.getRoute(),
                i.getRouteDetail(),
                i.getCreatedAt(),
                i.getCompletedAt(),
                evidence,
                i.getSteps().stream().map(step -> StepResponse.from(step, serverNames)).toList()
        );
    }
}
