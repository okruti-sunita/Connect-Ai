package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The heart of the system: the loop.
 * ask planner what to do next -> call that tool -> add its findings -> repeat -> rank -> answer.
 * It knows nothing about HTTP, databases, GitHub or LLMs - only the four small interfaces it is given.
 */
@Component
public class AgentOrchestrator {

    /** Safety net: an agent must never loop forever, even with a buggy planner. */
    static final int MAX_STEPS = 6;

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final ToolRegistry tools;
    private final Planner planner;
    private final EvidenceRanker ranker;
    private final AnswerGenerator answers;
    private final ToolExecutionPort toolExecutionPort;

    public AgentOrchestrator(
            ToolRegistry tools,
            Planner planner,
            EvidenceRanker ranker,
            AnswerGenerator answers,
            ToolExecutionPort toolExecutionPort) {
        this.tools = tools;
        this.planner = planner;
        this.ranker = ranker;
        this.answers = answers;
        this.toolExecutionPort = toolExecutionPort;
    }

    /**
     * Executes an externally provided tool through the application boundary.
     *
     * Task 5 intentionally does not let the planner choose arbitrary MCP tools
     * yet. That decision belongs to the next agent-planning task.
     */
    public ToolExecutionResult executeTool(ToolExecutionRequest request) {
        return toolExecutionPort.execute(request);
    }

    public AgentResult investigate(String question) {
        List<Finding> gathered = new ArrayList<>();
        Set<SourceType> queried = EnumSet.noneOf(SourceType.class);

        for (int step = 1; step <= MAX_STEPS; step++) {
            AgentState state = new AgentState(question, List.copyOf(gathered), Set.copyOf(queried), tools.connected());
            Optional<SourceType> next = planner.nextTool(state);
            if (next.isEmpty()) {
                log.info("Step {}: planner has nothing more to ask - stopping", step);
                break;
            }
            SourceType source = next.get();
            // Mark as asked BEFORE calling, so a failing tool is never retried in a loop.
            queried.add(source);
            gathered.addAll(callTool(step, source, question, gathered));
        }

        List<Finding> ranked = ranker.rank(gathered);
        return new AgentResult(ranked, answers.generate(question, ranked));
    }

    /** One broken tool must not sink the whole investigation (a requirement from the proposal). */
    private List<Finding> callTool(int step, SourceType source, String question, List<Finding> soFar) {
        try {
            List<Finding> found = tools.find(source).orElseThrow().gather(question, List.copyOf(soFar));
            log.info("Step {}: {} returned {} finding(s)", step, source, found.size());
            return found;
        } catch (RuntimeException e) {
            log.warn("Step {}: {} failed ({}) - continuing without it", step, source, e.getMessage());
            return List.of();
        }
    }
}
