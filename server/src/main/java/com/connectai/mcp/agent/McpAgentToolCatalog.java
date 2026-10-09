package com.connectai.mcp.agent;

import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.McpToolCandidate;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Infrastructure adapter exposing tools from enabled, connected MCP servers. */
@Component
public class McpAgentToolCatalog implements AgentToolCatalog {
    private final McpServerRepository serverRepository;
    private final McpClientRegistry clientRegistry;

    public McpAgentToolCatalog(McpServerRepository serverRepository, McpClientRegistry clientRegistry) {
        this.serverRepository = serverRepository;
        this.clientRegistry = clientRegistry;
    }

    @Override
    public List<McpToolCandidate> getAvailableTools() {
        List<McpToolCandidate> candidates = new ArrayList<>();
        for (UUID serverId : clientRegistry.connectedServerIds()) {
            collectServerTools(serverId, candidates);
        }
        return List.copyOf(candidates);
    }

    private void collectServerTools(UUID serverId, List<McpToolCandidate> candidates) {
        var server = serverRepository.findById(serverId).orElse(null);
        if (server == null || !server.isEnabled() || server.getStatus() != McpConnectionStatus.CONNECTED) return;
        var client = clientRegistry.find(serverId).orElse(null);
        if (client == null) return;
        try {
            String cursor = null;
            do {
                var page = client.listTools(cursor);
                if (page == null) break;
                if (page.tools() != null) {
                    for (McpSchema.Tool tool : page.tools()) {
                        if (tool == null || tool.name() == null || tool.name().isBlank()) continue;
                        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
                        candidates.add(new McpToolCandidate(serverId, tool.name(), tool.title(), tool.description(), schema));
                    }
                }
                String next = page.nextCursor();
                if (next == null || next.isBlank() || next.equals(cursor)) break;
                cursor = next;
            } while (true);
        } catch (RuntimeException ignored) {
            // A single unavailable MCP server must not hide tools from other servers.
        }
    }
}
