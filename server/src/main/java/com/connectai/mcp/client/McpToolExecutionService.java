package com.connectai.mcp.client;

import com.connectai.agent.ToolArgumentValidator;
import com.connectai.mcp.api.dto.McpToolExecutionRequest;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
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

/**
 * Invokes one tool on a live MCP session.
 *
 * HTTP semantics (used by both the direct endpoint and the agent adapter):
 * <ul>
 *     <li>404 - server or tool does not exist</li>
 *     <li>409 - the server exists but has no live session (not connected, or the
 *               session expired); the caller must connect first. This is intentional.</li>
 *     <li>400 - arguments do not satisfy the tool's input schema</li>
 *     <li>502 - transport/protocol failure while calling the tool</li>
 *     <li>200 with isError=true - the tool itself reported an error</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpToolExecutionService {
    private static final int MAX_TOOL_PAGES = 100;

    private final McpServerRepository repository;
    private final McpClientRegistry clientRegistry;

    public McpToolExecutionResponse execute(
            UUID serverId,
            String toolName,
            McpToolExecutionRequest request) {

        Map<String, Object> rawArguments = request == null || request.arguments() == null
                ? Map.of()
                : request.arguments();

        log.info("MCP tool execution requested: serverId={}, toolName={}, argumentKeys={}",
                serverId, toolName, rawArguments.keySet());

        EMcpServer server = repository.findById(serverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP server " + serverId + " not found"));

        if (toolName == null || toolName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Tool name must not be blank");
        }

        McpSyncClient client = clientRegistry.find(serverId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "MCP server '" + server.getName() + "' is not connected. Connect it first, then retry."));

        long started = System.nanoTime();
        try {
            McpSchema.Tool tool = findTool(client, toolName);

            ToolArgumentValidator.Result validation = ToolArgumentValidator.validate(
                    tool.inputSchema(), rawArguments);
            if (!validation.valid()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid arguments for '" + toolName + "': " + String.join("; ", validation.errors()));
            }

            McpSchema.CallToolRequest callToolRequest =
                    McpSchema.CallToolRequest.builder(toolName)
                            .arguments(validation.arguments())
                            .build();

            McpSchema.CallToolResult result = client.callTool(callToolRequest);

            if (result == null) {
                throw new IllegalStateException("MCP server returned no tool execution result");
            }

            log.info("MCP tool returned: serverId={}, toolName={}, isError={}, contentItems={}, structuredContentPresent={}, elapsedMs={}",
                    serverId, toolName, result.isError(),
                    result.content() == null ? 0 : result.content().size(),
                    result.structuredContent() != null, elapsedMs(started));
            return McpToolExecutionResponse.from(serverId, toolName, result);

        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            if (isSessionGone(exception)) {
                // The remote side dropped the session: stop advertising it as live.
                clientRegistry.remove(serverId);
                server.markStale(McpConnectionService.STALE_SESSION_MESSAGE);
                repository.save(server);
                log.warn("MCP session for serverId={} is gone; marked DISCONNECTED", serverId);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The MCP session for '" + server.getName() + "' has expired. Reconnect and retry.",
                        exception);
            }

            String detail = McpConnectionService.describeFailure(exception);
            log.warn("MCP tool execution failed: serverId={}, toolName={}, elapsedMs={}, cause={}",
                    serverId, toolName, elapsedMs(started), detail);
            log.debug("MCP tool execution failure type: exceptionType={}", exception.getClass().getSimpleName());
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "MCP tool '" + toolName + "' failed: " + detail,
                    exception);
        }
    }

    private static boolean isSessionGone(Throwable exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            if (t instanceof McpTransportSessionNotFoundException
                    || t instanceof McpTransportSessionClosedException) {
                return true;
            }
        }
        return false;
    }

    private McpSchema.Tool findTool(McpSyncClient client, String toolName) {
        return listAllTools(client).stream()
                .filter(tool -> toolName.equals(tool.name()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "MCP tool '" + toolName + "' is not exposed by the connected server"));
    }

    private List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> tools = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();

        McpSchema.ListToolsResult result = client.listTools();
        int pages = 0;

        while (result != null) {
            if (++pages > MAX_TOOL_PAGES) {
                throw new IllegalStateException("MCP tool execution exceeded the maximum page count");
            }
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

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
