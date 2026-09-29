package com.connectai.repository;

import com.connectai.domain.Investigation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvestigationRepository extends JpaRepository<Investigation, Long> {

    List<Investigation> findTop20ByOrderByCreatedAtDesc();

    boolean existsByQuestion(String question);
}
