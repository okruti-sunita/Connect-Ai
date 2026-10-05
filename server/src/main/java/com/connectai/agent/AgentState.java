package com.connectai.agent;

import com.connectai.domain.SourceType;

import java.util.List;
import java.util.Set;

/** Snapshot handed to the Planner every time it has to choose the next step. */
public record AgentState(
        String question,
        List<Finding> evidence,
        Set<SourceType> queried,
        Set<SourceType> available
) {
}
