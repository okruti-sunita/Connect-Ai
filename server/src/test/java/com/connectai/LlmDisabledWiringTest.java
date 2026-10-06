package com.connectai;

import com.connectai.agent.AnswerGenerator;
import com.connectai.agent.Planner;
import com.connectai.agent.RuleBasedPlanner;
import com.connectai.agent.TemplateAnswerGenerator;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.mcp.client.McpToolExecutionAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Default configuration: the agent must still get the Step 2 rules and template. */
@SpringBootTest(properties = "connectai.seed-demo-data=false")
class LlmDisabledWiringTest {

    @Autowired
    private Planner planner;

    @Autowired
    private AnswerGenerator answers;

    @Autowired
    private ToolExecutionPort toolExecutionPort;

    @Test
    void usesTheStep2RulesAndTemplate() {
        assertThat(planner).isInstanceOf(RuleBasedPlanner.class);
        assertThat(answers).isInstanceOf(TemplateAnswerGenerator.class);
        assertThat(toolExecutionPort).isInstanceOf(McpToolExecutionAdapter.class);
    }
}
