package com.connectai.repository;

import com.connectai.domain.LearnedRecipe;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LearnedRecipeRepository extends JpaRepository<LearnedRecipe, UUID> {

    Optional<LearnedRecipe> findByQuestionTemplate(String questionTemplate);

    List<LearnedRecipe> findAllByOrderByCreatedAtDesc();
}
