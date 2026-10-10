package com.connectai.agent.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentResource {

    private final AgentChatService chatService;
    private final AgentConversationHistoryService historyService;

    public AgentResource(
            AgentChatService chatService,
            AgentConversationHistoryService historyService
    ) {
        this.chatService = chatService;
        this.historyService = historyService;
    }

    @PostMapping("/chat")
    public ResponseEntity<AgentChatResponse> chat(
            @Valid @RequestBody AgentChatRequest request
    ) {
        return ResponseEntity.ok(
                chatService.chat(request.message())
        );
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<AgentConversationSummaryResponse>>
    listConversations() {
        return ResponseEntity.ok(
                historyService.listLatest()
        );
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<AgentConversationDetailResponse>
    getConversation(@PathVariable UUID id) {
        return ResponseEntity.ok(
                historyService.get(id)
        );
    }
}

