package com.connectai.agent.api;

import com.connectai.agent.GroundedEvidence;
import java.util.UUID;

public record GroundedEvidenceResponse(String citation, int stepNumber, UUID serverId, String toolName, String content) {
    public static GroundedEvidenceResponse from(GroundedEvidence evidence) {
        return new GroundedEvidenceResponse(evidence.citation(), evidence.stepNumber(), evidence.serverId(), evidence.toolName(), evidence.content());
    }
}
