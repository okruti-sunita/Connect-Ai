package com.connectai.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgentConversationRepository extends JpaRepository<AgentConversation, UUID> {
    List<AgentConversation> findTop20ByOrderByCreatedAtDesc();
}
