package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Simple, readable rules. The point is not that they are smart - it is that each decision
 * can depend on evidence found earlier, which is exactly what the LLM planner will do later.
 */
@Component
public class RuleBasedPlanner implements Planner {

    /** Matches ticket keys such as JIRA-123 or ORDER-4821. */
    private static final Pattern TICKET_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]+-\\d+\\b");

    private static final List<String> RUNTIME_HINTS =
            List.of("fail", "error", "exception", "spike", "stuck", "timeout", "down", "slow", "crash");

    @Override
    public Optional<SourceType> nextTool(AgentState state) {
        // 1. Code history is always the starting point.
        if (canAsk(state, SourceType.GITHUB)) {
            return Optional.of(SourceType.GITHUB);
        }
        // 2. Only ask Jira once a ticket key has actually appeared.
        if (canAsk(state, SourceType.JIRA) && mentionsTicket(state)) {
            return Optional.of(SourceType.JIRA);
        }
        // 3. Only search logs if the question sounds like something broke at runtime.
        if (canAsk(state, SourceType.SPLUNK) && looksLikeRuntimeIssue(state)) {
            return Optional.of(SourceType.SPLUNK);
        }
        // 4. Finally, check whether people were already talking about it.
        if (canAsk(state, SourceType.SLACK)) {
            return Optional.of(SourceType.SLACK);
        }
        return Optional.empty();
    }

    private boolean canAsk(AgentState state, SourceType tool) {
        return state.available().contains(tool) && !state.queried().contains(tool);
    }

    private boolean mentionsTicket(AgentState state) {
        if (TICKET_KEY.matcher(state.question()).find()) {
            return true;
        }
        return state.evidence().stream().anyMatch(f ->
                TICKET_KEY.matcher(f.summary()).find() || TICKET_KEY.matcher(f.sourceRef()).find());
    }

    private boolean looksLikeRuntimeIssue(AgentState state) {
        String q = state.question().toLowerCase(Locale.ROOT);
        return RUNTIME_HINTS.stream().anyMatch(q::contains);
    }
}
