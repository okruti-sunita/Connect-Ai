package com.connectai.repository;

import com.connectai.domain.Investigation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestigationRepository extends JpaRepository<Investigation, UUID> {

    List<Investigation> findTop20ByOrderByCreatedAtDesc();

    boolean existsByQuestion(String question);

}
