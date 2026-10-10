package com.connectai.agent.api;

import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.persistence.AgentConversation;
import com.connectai.agent.persistence.AgentConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConversationHistoryServiceTest {
    @Test
    void listsLatestTwentyConversationsAsSummaries() {
        AgentConversationRepository repository = mock(AgentConversationRepository.class);
        AgentConversation conversation = new AgentConversation("Why did checkout fail?");
        when(repository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(conversation));

        var result = new AgentConversationHistoryService(repository).listLatest();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).question()).isEqualTo("Why did checkout fail?");
        assertThat(result.get(0).status()).isEqualTo(AgentExecutionStatus.RUNNING);
    }

    @Test
    void getsConversationDetailById() {
        AgentConversationRepository repository = mock(AgentConversationRepository.class);
        AgentConversation conversation = new AgentConversation("Why did checkout fail?");
        UUID id = UUID.randomUUID();
        conversation.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(conversation));

        var result = new AgentConversationHistoryService(repository).get(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.question()).isEqualTo("Why did checkout fail?");
        assertThat(result.steps()).isEmpty();
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void returnsNotFoundWhenConversationDoesNotExist() {
        AgentConversationRepository repository = mock(AgentConversationRepository.class);
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AgentConversationHistoryService(repository).get(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
    }
}
