package com.connectai.mcp.client;

import com.connectai.mcp.api.dto.McpDiscoveredTool;
import com.connectai.mcp.api.dto.McpToolDiscoveryResponse;
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
public class McpToolDiscoveryService {
    private static final int MAX_TOOL_PAGES = 100;

    private final McpServerRepository repository;
    private final McpClientRegistry clientRegistry;

    public McpToolDiscoveryResponse discover(UUID serverId) {
        log.info("MCP discovery requested: serverId={}", serverId);
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
            log.info("MCP discovery completed: serverId={}, toolCount={}, toolNames={}", serverId, discoveredTools.size(), discoveredTools.stream().map(McpSchema.Tool::name).toList());

            List<McpDiscoveredTool> tools = discoveredTools.stream()
                    .map(this::toResponse)
                    .toList();

            return new McpToolDiscoveryResponse(serverId, tools);
        } catch (Exception exception) {
            log.error("MCP discovery failed: serverId={}, exceptionType={}",
                    serverId, exception.getClass().getSimpleName());
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
        log.debug("MCP listTools first page: nullResult={}", result == null);
        int pages = 0;

        while (result != null) {
            if (++pages > MAX_TOOL_PAGES) {
                throw new IllegalStateException("MCP tool discovery exceeded the maximum page count");
            }
            if (result.tools() != null) {
                tools.addAll(result.tools());
                log.debug("MCP listTools page: pageTools={}, cumulativeTools={}, hasNextCursor={}", result.tools().size(), tools.size(), result.nextCursor() != null && !result.nextCursor().isBlank());
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
