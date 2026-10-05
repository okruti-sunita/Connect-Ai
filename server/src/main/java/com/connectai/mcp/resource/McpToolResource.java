package com.connectai.mcp.resource;

import com.connectai.mcp.api.dto.RegisterMcpServerRequest;
import com.connectai.mcp.model.McpServer;
import com.connectai.mcp.operation.McpToolOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tools")
public class McpToolResource {

    private final McpToolOperations mcpToolOperations;

    @PostMapping
    public ResponseEntity<McpServer> registerTool(
            @Valid @RequestBody RegisterMcpServerRequest request) {

        McpServer registeredTool = mcpToolOperations.registerTool(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(registeredTool);
    }

    @GetMapping
    public ResponseEntity<List<McpServer>> getTools() {
        return ResponseEntity.ok(mcpToolOperations.getTools());
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServer> getTool(
            @PathVariable @Positive(message = "id must be positive") String id) {

        return ResponseEntity.ok(mcpToolOperations.getTool(UUID.fromString(id)));
    }
}
