package com.connectai.agent.api;

import com.connectai.agent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentResource.class)
@Import(AgentChatService.class)
class AgentResourceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AgentToolCatalog toolCatalog;

    @MockitoBean
    private ControlledAgentExecutor executor;

    @MockitoBean
    private GroundedAnswerGenerator answerGenerator;

    @Test
    void chatReturnsStableResponseShape() throws Exception {
        AgentExecutionState execution =
                AgentExecutionState.initial("Why did checkout fail?")
                        .withStatus(AgentExecutionStatus.COMPLETED);

        GroundedAnswer answer = new GroundedAnswer(
                "There is insufficient evidence to answer this question.",
                List.of(),
                AgentExecutionStatus.COMPLETED,
                false,
                true);

        when(toolCatalog.getAvailableTools())
                .thenReturn(List.of());

        when(executor.execute("Why did checkout fail?", List.of()))
                .thenReturn(execution);

        when(answerGenerator.generate(execution))
                .thenReturn(answer);

        mvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"Why did checkout fail?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.question")
                        .value("Why did checkout fail?"))
                .andExpect(jsonPath("$.answer")
                        .value("There is insufficient evidence to answer this question."))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.fallbackUsed").value(true))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.evidence").isArray());
    }

    @Test
    void blankMessageReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"   "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingMessageReturnsBadRequest() throws Exception {
        mvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
