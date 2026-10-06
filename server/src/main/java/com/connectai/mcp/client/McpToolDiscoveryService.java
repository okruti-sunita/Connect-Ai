package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpDiscoveredTool;
import com.connectai.mcp.api.dto.McpToolDiscoveryResponse;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
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
public class McpToolDiscoveryService {

    private final McpServerRepository repository;
    private final McpClientRegistry clientRegistry;

    public McpToolDiscoveryResponse discover(UUID serverId) {
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

        try {
            List<McpSchema.Tool> discoveredTools = listAllTools(client);

            List<McpDiscoveredTool> tools = discoveredTools.stream()
                    .map(this::toResponse)
                    .toList();

            return new McpToolDiscoveryResponse(serverId, tools);
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "MCP tool discovery failed for server " + serverId,
                    exception);
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
                        "MCP tool discovery returned a repeated pagination cursor");
            }

            result = client.listTools(cursor);
        }

        return tools;
    }

    private McpDiscoveredTool toResponse(McpSchema.Tool tool) {
        Map<String, Object> inputSchema =
                tool.inputSchema() == null ? Map.of() : tool.inputSchema();

        Map<String, Object> outputSchema =
                tool.outputSchema() == null ? Map.of() : tool.outputSchema();

        return new McpDiscoveredTool(
                tool.name(),
                tool.title(),
                tool.description(),
                inputSchema,
                outputSchema);
    }
}
