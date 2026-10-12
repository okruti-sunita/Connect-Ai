package com.connectai.api.dto;

import com.connectai.domain.Investigation;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.InvestigationStep;
import com.connectai.agent.AgentStepStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InvestigationResponse(
        UUID id,
        String title,
        String question,
        InvestigationStatus status,
        boolean archived,
        String resolvedAnswer,
        Instant createdAt,
        List<EvidenceResponse> evidence,
        List<StepResponse> steps
) {
    public record StepResponse(int stepNumber, String toolName, AgentStepStatus status) {
        static StepResponse from(InvestigationStep step) {
            return new StepResponse(step.getStepNumber(), step.getToolName(), step.getStatus());
        }
    }

    public static InvestigationResponse from(Investigation i) {
        List<EvidenceResponse> evidence = i.getEvidenceItems().stream()
                .map(EvidenceResponse::from)
                .toList();

        return new InvestigationResponse(
                i.getId(),
                i.getTitle(),
                i.getQuestion(),
                i.getStatus(),
                i.isArchived(),
                i.getResolvedAnswer(),
                i.getCreatedAt(),
                evidence,
                i.getSteps().stream().map(StepResponse::from).toList()
        );
    }
}