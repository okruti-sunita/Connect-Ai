package com.connectai.repository;

import com.connectai.domain.Investigation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.connectai.domain.InvestigationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestigationRepository
        extends JpaRepository<Investigation, UUID>, JpaSpecificationExecutor<Investigation> {

    List<Investigation> findTop20ByOrderByCreatedAtDesc();

    boolean existsByQuestion(String question);

    Optional<Investigation> findFirstByQuestionAndStatusOrderByCreatedAtDesc(String question, InvestigationStatus status);

    List<Investigation> findByStatus(InvestigationStatus status);

}
