package com.connectai.domain;

import com.connectai.jpa.base.AbstractUUIDPersistable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A tool plan the AI agent found for a question that it then answered in full, stored so the next
 * question of the same shape replays it without the planner. See {@code RecipeGeneralizer}.
 */
@Entity
@Table(name = "learned_recipe")
@Getter
public class LearnedRecipe extends AbstractUUIDPersistable {

    @Column(name = "question_template", nullable = false, unique = true, length = 500)
    private String questionTemplate;

    @Column(name = "example_question", nullable = false, length = 2000)
    private String exampleQuestion;

    @Column(name = "steps_json", nullable = false, length = 8000)
    private String stepsJson;

    /** The connections (server ids, comma separated) that were available when it was learned. */
    @Column(nullable = false, length = 2000)
    private String connections;

    @Column(name = "source_investigation_id")
    private UUID sourceInvestigationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(nullable = false)
    private boolean disabled;

    @Column(name = "disabled_reason", length = 500)
    private String disabledReason;

    protected LearnedRecipe() {
        // required by JPA
    }

    public LearnedRecipe(String questionTemplate) {
        this.questionTemplate = questionTemplate;
    }

    /** (Re)learns the recipe from a new answered run; a disabled recipe becomes active again. */
    public void learn(String exampleQuestion, String stepsJson, String connections, UUID sourceInvestigationId) {
        this.exampleQuestion = exampleQuestion.length() > 2000 ? exampleQuestion.substring(0, 2000) : exampleQuestion;
        this.stepsJson = stepsJson;
        this.connections = connections;
        this.sourceInvestigationId = sourceInvestigationId;
        this.createdAt = Instant.now();
        this.useCount = 0;
        this.lastUsedAt = null;
        this.disabled = false;
        this.disabledReason = null;
    }

    public void used() {
        this.useCount++;
        this.lastUsedAt = Instant.now();
    }

    /** A replay that no longer answers the question fully: the AI agent plans it again (and may relearn). */
    public void disable(String reason) {
        this.disabled = true;
        this.disabledReason = reason == null ? null : reason.length() > 500 ? reason.substring(0, 497) + "..." : reason;
    }
}
