package com.connectai.agent.api;

import com.connectai.agent.AgentStepStatus;

public record AgentChatStepResponse(int stepNumber, String toolName, AgentStepStatus status) { }
