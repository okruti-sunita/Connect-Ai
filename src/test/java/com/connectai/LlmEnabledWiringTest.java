package com.connectai;

import com.connectai.agent.AnswerGenerator;
import com.connectai.agent.Planner;
import com.connectai.llm.LlmAnswerGenerator;
import com.connectai.llm.LlmPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** With the LLM switched on, the @Primary beans must win. The key is a dummy: no call is made here. */
@SpringBootTest(properties = {
        "connectai.seed-demo-data=false",
        "connectai.llm.enabled=true",
        "connectai.llm.api-key=dummy-key-for-wiring-test"})
class LlmEnabledWiringTest {

    @Autowired
    private Planner planner;

    @Autowired
    private AnswerGenerator answers;

    @Test
    void usesTheLlmVersionsAsThePrimaryBeans() {
        assertThat(planner).isInstanceOf(LlmPlanner.class);
        assertThat(answers).isInstanceOf(LlmAnswerGenerator.class);
    }
}
