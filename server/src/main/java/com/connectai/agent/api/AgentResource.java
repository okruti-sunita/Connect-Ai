package com.connectai.agent.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentResource {
    private final AgentChatService chatService;
    public AgentResource(AgentChatService chatService) { this.chatService = chatService; }

    @PostMapping("/chat")
    public ResponseEntity<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request.message()));
    }
}
