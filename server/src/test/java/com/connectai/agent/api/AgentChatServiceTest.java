package com.connectai.agent.api;

import com.connectai.agent.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentChatServiceTest {
    @Test void chatTrimsQuestionAndReturnsGroundedAnswer() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ControlledAgentExecutor executor = mock(ControlledAgentExecutor.class);
        GroundedAnswerGenerator generator = mock(GroundedAnswerGenerator.class);
        AgentChatService service = new AgentChatService(catalog, executor, generator);
        AgentExecutionState execution = AgentExecutionState.initial("What happened?").withStatus(AgentExecutionStatus.COMPLETED);
        GroundedAnswer answer = new GroundedAnswer("Insufficient evidence.", List.of(), AgentExecutionStatus.COMPLETED, false, true);
        when(catalog.getAvailableTools()).thenReturn(List.of());
        when(executor.execute("What happened?", List.of())).thenReturn(execution);
        when(generator.generate(execution)).thenReturn(answer);
        AgentChatResponse response = service.chat("  What happened?  ");
        assertThat(response.question()).isEqualTo("What happened?");
        assertThat(response.status()).isEqualTo(AgentExecutionStatus.COMPLETED);
        assertThat(response.answer()).isEqualTo("Insufficient evidence.");
        assertThat(response.grounded()).isFalse();
        assertThat(response.fallbackUsed()).isTrue();
        verify(catalog).getAvailableTools();
        verify(executor).execute("What happened?", List.of());
        verify(generator).generate(execution);
    }

    @Test void blankMessageIsRejectedBeforeDependenciesAreCalled() {
        AgentToolCatalog catalog = mock(AgentToolCatalog.class);
        ControlledAgentExecutor executor = mock(ControlledAgentExecutor.class);
        GroundedAnswerGenerator generator = mock(GroundedAnswerGenerator.class);
        AgentChatService service = new AgentChatService(catalog, executor, generator);
        assertThrows(IllegalArgumentException.class, () -> service.chat("   "));
        verifyNoInteractions(catalog, executor, generator);
    }
}
