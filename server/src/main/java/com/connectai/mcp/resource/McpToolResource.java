package com.connectai.mcp.resource;

import com.connectai.mcp.api.dto.*;
import com.connectai.mcp.client.McpToolDiscoveryService;
import com.connectai.mcp.client.McpToolExecutionService;
import com.connectai.mcp.model.McpServer;
import com.connectai.mcp.client.McpConnectionService;
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
    private final McpConnectionService mcpConnectionService;
    private final McpToolExecutionService mcpToolExecutionService;
    private final McpToolDiscoveryService mcpToolDiscoveryService;

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

    @PostMapping("/{id}/connect")
    public ResponseEntity<McpConnectionResponse> connect(
            @PathVariable String id) {

        return ResponseEntity.ok(mcpConnectionService.connect(UUID.fromString(id)));
    }

    @PostMapping("/{id}/disconnect")
    public ResponseEntity<McpConnectionResponse> disconnect(
            @PathVariable String id) {

        return ResponseEntity.ok(mcpConnectionService.disconnect(UUID.fromString(id)));
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<McpToolDiscoveryResponse> discoverTools(@PathVariable UUID id) {
        return ResponseEntity.ok(mcpToolDiscoveryService.discover(id));
    }

    @GetMapping("/{id}/connection")
    public ResponseEntity<McpConnectionResponse> connectionStatus(
            @PathVariable UUID id) {

        return ResponseEntity.ok(mcpConnectionService.status(id));
    }

    @PostMapping("/{id}/tools/{toolName}/execute")
    public ResponseEntity<McpToolExecutionResponse> executeTool(
            @PathVariable UUID id,
            @PathVariable String toolName,
            @RequestBody(required = false) McpToolExecutionRequest request) {
        return ResponseEntity.ok(mcpToolExecutionService.execute(id, toolName, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpServer> getTool(
            @PathVariable  String id) {

        return ResponseEntity.ok(mcpToolOperations.getTool(UUID.fromString(id)));
    }
}
