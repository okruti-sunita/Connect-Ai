package com.connectai.agent.api;

import com.connectai.agent.*;
import com.connectai.agent.persistence.AgentConversation;
import com.connectai.agent.persistence.AgentConversationRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {

    @Test
    void chatTrimsQuestionAndReturnsGroundedAnswer() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ControlledAgentExecutor executor = mock(ControlledAgentExecutor.class);
        GroundedAnswerGenerator generator = mock(GroundedAnswerGenerator.class);
        AgentConversationRepository repository =
                mock(AgentConversationRepository.class);

        UUID conversationId = UUID.randomUUID();

        when(repository.saveAndFlush(any(AgentConversation.class)))
                .thenAnswer(invocation -> {
                    AgentConversation conversation = invocation.getArgument(0);
                    conversation.setId(conversationId);
                    return conversation;
                });

        when(repository.save(any(AgentConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AgentChatService service =
                new AgentChatService(catalog, executor, generator, repository);

        AgentExecutionState execution = AgentExecutionState.initial("What happened?")
                .withStatus(AgentExecutionStatus.COMPLETED);

        GroundedAnswer answer = new GroundedAnswer(
                "Insufficient evidence.",
                List.of(),
                AgentExecutionStatus.COMPLETED,
                false,
                true
        );

        when(catalog.getAvailableTools()).thenReturn(List.of());
        when(executor.execute("What happened?", List.of())).thenReturn(execution);
        when(generator.generate(execution)).thenReturn(answer);

        AgentChatResponse response = service.chat("  What happened?  ");

        assertThat(response.id()).isEqualTo(conversationId);
        assertThat(response.question()).isEqualTo("What happened?");
        assertThat(response.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(response.answer()).isEqualTo("Insufficient evidence.");
        assertThat(response.grounded()).isFalse();
        assertThat(response.fallbackUsed()).isTrue();

        verify(repository).saveAndFlush(any(AgentConversation.class));
        verify(repository).save(any(AgentConversation.class));
        verify(catalog).getAvailableTools();
        verify(executor).execute("What happened?", List.of());
        verify(generator).generate(execution);
    }

    @Test
    void blankMessageIsRejectedBeforeDependenciesAreCalled() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ControlledAgentExecutor executor = mock(ControlledAgentExecutor.class);
        GroundedAnswerGenerator generator = mock(GroundedAnswerGenerator.class);
        AgentConversationRepository repository =
                mock(AgentConversationRepository.class);

        AgentChatService service =
                new AgentChatService(catalog, executor, generator, repository);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.chat("   ")
        );

        verifyNoInteractions(catalog, executor, generator, repository);
    }

    @Test
    void executionFailureMarksConversationFailed() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ControlledAgentExecutor executor = mock(ControlledAgentExecutor.class);
        GroundedAnswerGenerator generator = mock(GroundedAnswerGenerator.class);
        AgentConversationRepository repository =
                mock(AgentConversationRepository.class);

        when(repository.saveAndFlush(any(AgentConversation.class)))
                .thenAnswer(invocation -> {
                    AgentConversation conversation = invocation.getArgument(0);
                    conversation.setId(UUID.randomUUID());
                    return conversation;
                });

        when(repository.save(any(AgentConversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        when(catalog.getAvailableTools()).thenReturn(List.of());

        when(executor.execute("Test failure", List.of()))
                .thenThrow(new IllegalStateException("Tool execution failed"));

        AgentChatService service =
                new AgentChatService(catalog, executor, generator, repository);

        assertThrows(
                IllegalStateException.class,
                () -> service.chat("Test failure")
        );

        verify(repository).saveAndFlush(any(AgentConversation.class));

        verify(repository).save(argThat(conversation ->
                conversation.getStatus() == AgentExecutionStatus.FAILED
                        && "The request could not be completed."
                        .equals(conversation.getAnswer())
        ));

        verify(catalog).getAvailableTools();
        verify(executor).execute("Test failure", List.of());
        verifyNoInteractions(generator);
    }
}
