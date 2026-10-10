package com.connectai.agent.api;

import com.connectai.agent.*;
import com.connectai.agent.persistence.AgentConversationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    private AgentConversationRepository conversationRepository;

    @MockitoBean
    private AgentConversationHistoryService historyService;

    @Test
    void chatReturnsStableResponseShape() throws Exception {
        when(conversationRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> {
                    var conversation = invocation.getArgument(
                            0,
                            com.connectai.agent.persistence.AgentConversation.class
                    );
                    conversation.setId(java.util.UUID.randomUUID());
                    return conversation;
                });

        when(conversationRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AgentExecutionState execution =
                AgentExecutionState.initial("Why did checkout fail?")
                        .withStatus(AgentExecutionStatus.COMPLETED);

        GroundedAnswer answer = new GroundedAnswer(
                "There is insufficient evidence to answer this question.",
                List.of(),
                AgentExecutionStatus.COMPLETED,
                false,
                true
        );

        when(toolCatalog.getAvailableTools()).thenReturn(List.of());
        when(executor.execute("Why did checkout fail?", List.of()))
                .thenReturn(execution);
        when(answerGenerator.generate(execution)).thenReturn(answer);

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

        verify(conversationRepository).saveAndFlush(any());
        verify(conversationRepository).save(any());
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

    @Test
    void listConversationsReturnsHistory() throws Exception {
        when(historyService.listLatest()).thenReturn(List.of());

        mvc.perform(get("/api/agent/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(historyService).listLatest();
    }
}
