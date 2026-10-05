package com.connectai.agent;

import java.util.List;

/** What one investigation produces: ranked evidence plus the answer built from it. */
public record AgentResult(List<Finding> evidence, String answer) {
}
