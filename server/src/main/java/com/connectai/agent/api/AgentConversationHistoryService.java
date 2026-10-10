package com.connectai.agent.api;

import com.connectai.agent.persistence.AgentConversationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@Service
public class AgentConversationHistoryService {
    private final AgentConversationRepository repository;

    public AgentConversationHistoryService(AgentConversationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AgentConversationSummaryResponse> listLatest() {
        return repository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(AgentConversationSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentConversationDetailResponse get(UUID id) {
        var conversation = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return AgentConversationDetailResponse.from(conversation);
    }
}
