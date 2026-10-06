package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutionRequestTest {

    @Test
    void nullArgumentsBecomeAnEmptyImmutableMap() {
        ToolExecutionRequest request = new ToolExecutionRequest(
                UUID.randomUUID(),
                "health_check",
                null);

        assertThat(request.arguments()).isEmpty();
        assertThatThrownBy(() -> request.arguments().put("unexpected", true))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void callerCannotMutateArgumentsAfterRequestCreation() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("message", "hello");

        ToolExecutionRequest request = new ToolExecutionRequest(
                UUID.randomUUID(),
                "echo",
                arguments);

        arguments.put("message", "changed");

        assertThat(request.arguments())
                .containsEntry("message", "hello");
    }

    @Test
    void blankToolNameIsRejectedAtTheApplicationBoundary() {
        assertThatThrownBy(() -> new ToolExecutionRequest(
                UUID.randomUUID(),
                "   ",
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("toolName must not be blank");
    }
}
