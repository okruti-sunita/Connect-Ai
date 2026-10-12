package com.connectai.api;

import com.connectai.domain.LearnedRecipe;
import com.connectai.service.LearnedRecipeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The recipes the engine learned from answered AI runs (self-learning fast path). */
@RestController
@RequestMapping("/api/insights/learned")
public class LearnedRecipeController {

    private final LearnedRecipeService learned;

    public LearnedRecipeController(ObjectProvider<LearnedRecipeService> learned) {
        this.learned = learned.getIfAvailable();
    }

    public record LearnedRecipeDto(UUID id, String questionTemplate, String exampleQuestion, List<String> tools,
                                   UUID sourceInvestigationId, Instant createdAt, int useCount, Instant lastUsedAt,
                                   boolean disabled, String disabledReason) {

        static LearnedRecipeDto from(LearnedRecipe recipe) {
            List<String> tools = java.util.regex.Pattern.compile("\"toolName\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(recipe.getStepsJson()).results().map(m -> m.group(1)).distinct().toList();
            return new LearnedRecipeDto(recipe.getId(), recipe.getQuestionTemplate(), recipe.getExampleQuestion(), tools,
                    recipe.getSourceInvestigationId(), recipe.getCreatedAt(), recipe.getUseCount(), recipe.getLastUsedAt(),
                    recipe.isDisabled(), recipe.getDisabledReason());
        }
    }

    @GetMapping
    public List<LearnedRecipeDto> list() {
        return learned == null ? List.of() : learned.all().stream().map(LearnedRecipeDto::from).toList();
    }

    /** Forget a recipe: the AI agent plans that question shape again (and may learn it again). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> forget(@PathVariable UUID id) {
        if (learned == null || !learned.forget(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No learned recipe " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
