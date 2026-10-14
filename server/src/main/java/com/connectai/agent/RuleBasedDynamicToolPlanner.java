package com.connectai.agent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic planner used when LLM mode is disabled.
 *
 * This planner is deliberately conservative. It never invents arguments for
 * an MCP tool that declares required input fields.
 */
public class RuleBasedDynamicToolPlanner implements DynamicToolPlanner {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedDynamicToolPlanner.class);

    private final ToolSemanticRetriever semanticRetriever;

    public RuleBasedDynamicToolPlanner() {
        this(ToolSemanticRetriever.disabled());
    }

    public RuleBasedDynamicToolPlanner(ToolSemanticRetriever semanticRetriever) {
        this.semanticRetriever = java.util.Objects.requireNonNull(semanticRetriever, "semanticRetriever");
    }

    @Override
    public Optional<ToolSelectionPlan> plan(
            String question,
            List<McpToolCandidate> availableTools) {
        return plan(question, availableTools, AgentExecutionState.initial(question));
    }

    @Override
    public Optional<ToolSelectionPlan> plan(
            String question,
            List<McpToolCandidate> availableTools,
            AgentExecutionState state) {

        if (question == null || question.isBlank()
                || availableTools == null || availableTools.isEmpty()) {
            return Optional.empty();
        }

        for (McpToolCandidate candidate : availableTools) {
            if (alreadyExecuted(candidate, state)) {
                continue;
            }
            if (!looksRelevant(question, candidate)) {
                continue;
            }
            if (!hasNoRequiredArguments(candidate.inputSchema())) {
                continue;
            }

            return Optional.of(new ToolSelectionPlan(
                    candidate.serverId(),
                    candidate.toolName(),
                    Map.of(),
                    "Selected by deterministic MCP tool metadata matching"));
        }

        // Optional semantic fallback uses the same live catalog and conservative schema rule.
        List<McpToolCandidate> semantic;
        try {
            semantic = semanticRetriever.retrieve(question, availableTools, 6);
        } catch (RuntimeException exception) {
            log.warn("Semantic tool retrieval failed; using lexical catalog only: {}",
                    SafeDiagnostic.message(exception, 240));
            semantic = List.of();
        }
        java.util.Map<String, McpToolCandidate> live = new java.util.HashMap<>();
        availableTools.forEach(candidate -> live.put(candidate.plannerId(), candidate));
        for (McpToolCandidate retrieved : semantic) {
            if (retrieved == null) continue;
            McpToolCandidate candidate = live.get(retrieved.plannerId());
            if (candidate == null || alreadyExecuted(candidate, state)
                    || !hasNoRequiredArguments(candidate.inputSchema())) continue;
            return Optional.of(new ToolSelectionPlan(candidate.serverId(), candidate.toolName(), Map.of(),
                    "Selected by semantic MCP tool metadata matching"));
        }

        return Optional.empty();
    }

    private boolean alreadyExecuted(
            McpToolCandidate candidate,
            AgentExecutionState state) {
        return state.steps().stream().anyMatch(step ->
                step.plan().serverId().equals(candidate.serverId())
                        && step.plan().toolName().equals(candidate.toolName()));
    }

    private boolean looksRelevant(
            String question,
            McpToolCandidate candidate) {
        String query = question.toLowerCase(Locale.ROOT);
        String metadata = (candidate.toolName() + " "
                + candidate.title() + " "
                + candidate.description()).toLowerCase(Locale.ROOT);

        for (String word : query.split("\\W+")) {
            if (word.length() >= 4 && metadata.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNoRequiredArguments(Map<String, Object> schema) {
        Object required = schema.get("required");
        return !(required instanceof List<?> list) || list.isEmpty();
    }
}

