package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpToolExecutionRequest;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolExecutionService {

    private final McpServerRepository repository;
    private final McpClientRegistry clientRegistry;

    public McpToolExecutionResponse execute(
            UUID serverId,
            String toolName,
            McpToolExecutionRequest request) {

        log.info("MCP tool execution requested: serverId={}, toolName={}, argumentKeys={}", serverId, toolName, request == null || request.arguments() == null ? List.of() : request.arguments().keySet());
        repository.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + serverId + " not found"));

        McpSyncClient client;
        try {
            client = clientRegistry.require(serverId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "MCP server " + serverId + " is not connected",
                    exception);
        }

        if (toolName == null || toolName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tool name must not be blank");
        }

        try {
            ensureToolExists(client, toolName);

            Map<String, Object> arguments = request == null || request.arguments() == null
                    ? Map.of()
                    : request.arguments();

            McpSchema.CallToolRequest callToolRequest =
                    McpSchema.CallToolRequest.builder(toolName)
                            .arguments(arguments)
                            .build();

            log.debug("Calling MCP tool: serverId={}, toolName={}", serverId, toolName);
            McpSchema.CallToolResult result = client.callTool(callToolRequest);

            if (result == null) {
                throw new IllegalStateException("MCP server returned no tool execution result");
            }

            log.info("MCP tool returned: serverId={}, toolName={}, isError={}, contentItems={}, structuredContentPresent={}", serverId, toolName, result.isError(), result.content() == null ? 0 : result.content().size(), result.structuredContent() != null);
            return McpToolExecutionResponse.from(serverId, toolName, result);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("MCP tool execution failed: serverId={}, toolName={}, cause={}", serverId, toolName, exception.toString(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "MCP tool execution failed for '" + toolName + "' on server " + serverId,
                    exception);
        }
    }

    private void ensureToolExists(McpSyncClient client, String toolName) {
        List<McpSchema.Tool> tools = listAllTools(client);

        boolean exists = tools.stream()
                .anyMatch(tool -> toolName.equals(tool.name()));

        if (!exists) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "MCP tool '" + toolName + "' is not exposed by the connected server");
        }
    }

    private List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();

        McpSchema.ListToolsResult result = client.listTools();

        while (result != null) {
            if (result.tools() != null) {
                tools.addAll(result.tools());
            }

            String cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                break;
            }

            if (!seenCursors.add(cursor)) {
                throw new IllegalStateException(
                        "MCP tool execution encountered a repeated pagination cursor");
            }

            result = client.listTools(cursor);
        }

        return tools;
    }
}
