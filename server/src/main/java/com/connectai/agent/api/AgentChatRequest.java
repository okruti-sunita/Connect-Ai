package com.connectai.agent.api;

import jakarta.validation.constraints.NotBlank;

public record AgentChatRequest(@NotBlank(message = "message must not be blank") String message) { }
