
package com.connectai.agent.persistence;

import com.connectai.agent.AgentExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConversationTest {

    @Test
    void newConversationStartsRunningAndRecordsCreationTime() {
        Instant before = Instant.now();

        AgentConversation conversation =
                new AgentConversation("Why did checkout fail?");

        Instant after = Instant.now();

        assertThat(conversation.getQuestion())
                .isEqualTo("Why did checkout fail?");

        assertThat(conversation.getStatus())
                .isEqualTo(AgentExecutionStatus.RUNNING);

        assertThat(conversation.getCreatedAt())
                .isBetween(before, after);

        assertThat(conversation.getCompletedAt()).isNull();

        assertThat(conversation.getAnswer()).isNull();

        assertThat(conversation.isGrounded()).isFalse();

        assertThat(conversation.isFallbackUsed()).isFalse();
    }

    @Test
    void markingConversationFailedSetsSafeFailureStateAndCompletionTime() {
        AgentConversation conversation =
                new AgentConversation("Why did checkout fail?");

        Instant before = Instant.now();

        conversation.markFailed();

        Instant after = Instant.now();

        assertThat(conversation.getStatus())
                .isEqualTo(AgentExecutionStatus.FAILED);

        assertThat(conversation.getAnswer())
                .isEqualTo("The request could not be completed.");

        assertThat(conversation.getCompletedAt())
                .isNotNull()
                .isBetween(before, after);

        assertThat(conversation.isGrounded()).isFalse();

        assertThat(conversation.isFallbackUsed()).isTrue();
    }
}