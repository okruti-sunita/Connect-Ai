package com.connectai.service;

import com.connectai.agent.AgentExecutionListener;
import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolSafetyPolicy;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.agent.fastpath.GenericToolMatcher;
import com.connectai.agent.learning.QuestionTemplate;
import com.connectai.agent.learning.RecipeGeneralizer;
import com.connectai.agent.learning.RecipeGeneralizer.Identity;
import com.connectai.agent.learning.RecipeGeneralizer.RecipeStep;
import com.connectai.domain.InvestigationStatus;
import com.connectai.domain.LearnedRecipe;
import com.connectai.repository.LearnedRecipeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Self-learning fast path. When the AI agent answers a question in full, its tool plan is stored as a
 * recipe keyed by the question's shape ({@link QuestionTemplate}). The next question of the same shape,
 * over the same connections, replays that plan with the new question's values: the planner (several
 * model calls) is skipped and one model call writes the answer from the fresh evidence.
 *
 * A replay that does not end ANSWERED disables its recipe, so the agent plans that shape again.
 */
@Component
@ConditionalOnProperty(name = "connectai.agent.learning.enabled", havingValue = "true", matchIfMissing = true)
public class LearnedRecipeService {

    private static final Logger log = LoggerFactory.getLogger(LearnedRecipeService.class);
    private static final TypeReference<List<RecipeStep>> STEPS = new TypeReference<>() { };
    private static final int MAX_STEPS_JSON = 8000;

    /** A replayed recipe: the execution, its answer, and the "why this route" explanation. */
    public record Replay(UUID recipeId, AgentExecutionState execution, GroundedAnswer answer, String explanation) {
    }

    private final LearnedRecipeRepository recipes;
    private final PlannedToolExecutor executor;
    private final ObjectMapper json;

    public LearnedRecipeService(LearnedRecipeRepository recipes, PlannedToolExecutor executor, ObjectMapper json) {
        this.recipes = recipes;
        this.executor = executor;
        this.json = json;
    }

    /** Replays the recipe learned for this question's shape; empty when there is none that applies. */
    public Optional<Replay> replay(String question, List<McpToolCandidate> tools, AgentExecutionListener listener,
                                  Function<AgentExecutionState, GroundedAnswer> answerWriter) {
        QuestionTemplate template = QuestionTemplate.of(question);
        Optional<LearnedRecipe> found = recipes.findByQuestionTemplate(template.template()).filter(r -> !r.isDisabled());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LearnedRecipe recipe = found.get();
        if (!recipe.getConnections().equals(connections(tools))) {
            log.info("Learned recipe {} not replayed: the connected tools changed since it was learned", recipe.getId());
            return Optional.empty();
        }
        List<RecipeStep> steps;
        try {
            steps = json.readValue(recipe.getStepsJson(), STEPS);
        } catch (JsonProcessingException unreadable) {
            recipe.disable("Stored plan could not be read: " + unreadable.getOriginalMessage());
            recipes.save(recipe);
            return Optional.empty();
        }
        Map<String, McpToolCandidate> catalog = new HashMap<>();
        tools.forEach(tool -> catalog.put(key(tool.serverId(), tool.toolName()), tool));
        for (RecipeStep step : steps) {
            McpToolCandidate tool = catalog.get(key(step.serverId(), step.toolName()));
            if (tool == null || !ToolSafetyPolicy.isReadOnly(tool)) {
                log.info("Learned recipe {} not replayed: {} is unavailable or not read-only", recipe.getId(), step.toolName());
                return Optional.empty();
            }
        }

        log.info("Replaying learned recipe {} ({} step(s)) for template \"{}\"", recipe.getId(), steps.size(), template.template());
        AgentExecutionListener progress = listener == null ? AgentExecutionListener.NONE : listener;
        AgentExecutionState state = AgentExecutionState.initial(question);
        Map<UUID, Identity> identities = new HashMap<>();
        String failure = null;
        for (RecipeStep step : steps) {
            Optional<Map<String, Object>> arguments = RecipeGeneralizer.instantiate(step, template.slots(), identities.get(step.serverId()));
            if (arguments.isEmpty()) {
                continue; // needs this connection's login, and its identity call failed (already recorded)
            }
            McpToolCandidate tool = catalog.get(key(step.serverId(), step.toolName()));
            AgentExecutionStep executed = executor.execute(state.stepCount() + 1, new ToolSelectionPlan(
                    step.serverId(), step.toolName(), arguments.get(), "Learned recipe: same plan as \"" + recipe.getExampleQuestion() + "\"."));
            state = state.addStep(executed);
            try {
                progress.onStep(executed);
            } catch (RuntimeException ignored) {
                // progress persistence must not break the run
            }
            if (executed.status() != AgentStepStatus.SUCCESS) {
                failure = step.toolName() + " failed" + (executed.errorMessage() == null ? "" : ": " + executed.errorMessage());
            } else if (GenericToolMatcher.isIdentityTool(tool)) {
                RecipeGeneralizer.identity(executed).ifPresent(identity -> identities.put(step.serverId(), identity));
            }
        }
        AgentExecutionState execution = state.finish(
                state.hasSuccessfulStep() ? AgentExecutionStatus.COMPLETED : AgentExecutionStatus.FAILED,
                failure == null
                        ? "Replayed a learned recipe: " + state.stepCount() + " tool call(s), planner skipped."
                        : "Learned recipe replay: " + failure);
        GroundedAnswer answer = answerWriter.apply(execution);

        recipe.used();
        recipes.save(recipe);
        String values = template.slots().isEmpty() ? "" : " with this question's values ("
                + template.slots().entrySet().stream().map(e -> e.getKey() + " = " + e.getValue()).collect(Collectors.joining(", ")) + ")";
        String explanation = "Same question shape as an earlier answered run (\"" + recipe.getExampleQuestion()
                + "\"), so its learned plan was replayed: "
                + steps.stream().map(RecipeStep::toolName).distinct().collect(Collectors.joining(" → "))
                + values + ". The planner was skipped; one model call wrote the answer from the new evidence. Used "
                + recipe.getUseCount() + " time(s).";
        return Optional.of(new Replay(recipe.getId(), execution, answer, explanation));
    }

    /** After a replay: a recipe that no longer answers fully is disabled, so the agent plans that shape again. */
    public void review(UUID recipeId, InvestigationStatus status) {
        if (status == InvestigationStatus.ANSWERED) {
            return;
        }
        recipes.findById(recipeId).ifPresent(recipe -> {
            recipe.disable("A replay ended " + status + "; the AI agent will plan this question again.");
            recipes.save(recipe);
            log.info("Learned recipe {} disabled after a replay ended {}", recipeId, status);
        });
    }

    /** Learns from an AI agent run that ended ANSWERED. Never throws: learning is best effort. */
    public void learn(UUID investigationId, String question, AgentExecutionState execution,
                      InvestigationStatus status, List<McpToolCandidate> tools) {
        if (status != InvestigationStatus.ANSWERED) {
            return;
        }
        try {
            QuestionTemplate template = QuestionTemplate.of(question);
            if (template.template().isBlank() || template.template().length() > 500) {
                return;
            }
            RecipeGeneralizer.Outcome outcome = RecipeGeneralizer.generalize(template, question, execution, tools);
            if (outcome.steps().isEmpty()) {
                log.info("Not learned from investigation {}: {}", investigationId, outcome.reason());
                return;
            }
            String stepsJson = json.writeValueAsString(outcome.steps().get());
            if (stepsJson.length() > MAX_STEPS_JSON) {
                log.info("Not learned from investigation {}: plan too large to store", investigationId);
                return;
            }
            LearnedRecipe recipe = recipes.findByQuestionTemplate(template.template())
                    .orElseGet(() -> new LearnedRecipe(template.template()));
            recipe.learn(question, stepsJson, connections(tools), investigationId);
            recipes.save(recipe);
            log.info("Learned recipe from investigation {}: \"{}\" -> {} step(s)", investigationId,
                    template.template(), outcome.steps().get().size());
        } catch (RuntimeException | JsonProcessingException failure) {
            log.warn("Learning from investigation {} failed: {}", investigationId, failure.toString());
        }
    }

    public List<LearnedRecipe> all() {
        return recipes.findAllByOrderByCreatedAtDesc();
    }

    public boolean forget(UUID id) {
        if (!recipes.existsById(id)) {
            return false;
        }
        recipes.deleteById(id);
        return true;
    }

    private static String connections(List<McpToolCandidate> tools) {
        return tools.stream().map(t -> t.serverId().toString()).collect(Collectors.toCollection(TreeSet::new))
                .stream().collect(Collectors.joining(","));
    }

    private static String key(UUID server, String tool) {
        return server + "/" + tool;
    }
}
