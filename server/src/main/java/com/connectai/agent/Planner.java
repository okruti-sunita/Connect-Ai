package com.connectai.agent;

import com.connectai.domain.SourceType;

import java.util.Optional;

/**
 * The agent's "brain": given what is known so far, which tool should be asked next?
 * Empty means "I have enough, stop". Step 2 uses rules; Step 3 swaps in an LLM.
 */
public interface Planner {

    Optional<SourceType> nextTool(AgentState state);
}
