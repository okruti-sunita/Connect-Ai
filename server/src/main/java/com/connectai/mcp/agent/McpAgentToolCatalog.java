package com.connectai.mcp.agent;

import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolSafetyPolicy;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Infrastructure adapter exposing tools from enabled, connected MCP servers. */
@Component
@Slf4j
public class McpAgentToolCatalog implements AgentToolCatalog {
    private final McpServerRepository serverRepository;
    private final McpClientRegistry clientRegistry;
    private final boolean allowWriteTools;

    public McpAgentToolCatalog(
            McpServerRepository serverRepository,
            McpClientRegistry clientRegistry,
            @Value("${connectai.agent.allow-write-tools:false}") boolean allowWriteTools) {
        this.serverRepository = serverRepository;
        this.clientRegistry = clientRegistry;
        this.allowWriteTools = allowWriteTools;
    }

    @Override
    public List<McpToolCandidate> getAvailableTools() {
        var connectedIds = clientRegistry.connectedServerIds();
        log.info("MCP catalog build started: connectedRuntimeServers={}", connectedIds.size());
        List<McpToolCandidate> candidates = new ArrayList<>();
        for (UUID serverId : connectedIds) {
            int before = candidates.size();
            collectServerTools(serverId, candidates);
            log.info("MCP catalog server scan finished: serverId={}, toolsAdded={}", serverId, candidates.size() - before);
        }
        log.info("MCP catalog build finished: availableTools={}, toolIds={}", candidates.size(),
                candidates.stream().map(McpToolCandidate::plannerId).toList());
        return List.copyOf(candidates);
    }

    private void collectServerTools(UUID serverId, List<McpToolCandidate> candidates) {
        var server = serverRepository.findById(serverId).orElse(null);
        if (server == null) {
            log.warn("MCP catalog skipped runtime server because database row is missing: serverId={}", serverId);
            return;
        }
        if (!server.isEnabled() || server.getStatus() != McpConnectionStatus.CONNECTED) {
            log.warn("MCP catalog skipped server: serverId={}, enabled={}, persistedStatus={}",
                    serverId, server.isEnabled(), server.getStatus());
            return;
        }
        var client = clientRegistry.find(serverId).orElse(null);
        if (client == null) {
            log.warn("MCP catalog skipped server because runtime client is missing: serverId={}", serverId);
            return;
        }
        log.debug("MCP catalog listing tools: serverId={}, serverName={}", serverId, server.getName());
        try {
            String cursor = null;
            java.util.Set<String> seenNames = new java.util.HashSet<>();
            List<String> excludedWrites = new ArrayList<>();
            do {
                var page = client.listTools(cursor);
                if (page == null) {
                    log.warn("MCP listTools returned null page: serverId={}", serverId);
                    break;
                }
                log.debug("MCP listTools page received: serverId={}, toolCount={}, hasNextCursor={}",
                        serverId, page.tools() == null ? 0 : page.tools().size(),
                        page.nextCursor() != null && !page.nextCursor().isBlank());
                if (page.tools() != null) {
                    for (McpSchema.Tool tool : page.tools()) {
                        if (tool == null || tool.name() == null || tool.name().isBlank()) continue;
                        if (!seenNames.add(tool.name())) {
                            log.warn("MCP server listed tool '{}' twice; keeping the first: serverId={}", tool.name(), serverId);
                            continue;
                        }
                        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
                        Boolean readOnly = tool.annotations() == null ? null : tool.annotations().readOnlyHint();
                        McpToolCandidate candidate = new McpToolCandidate(
                                serverId, tool.name(), tool.title(), tool.description(), schema, readOnly, server.getName());
                        // Investigations gather evidence; tools that change data are not offered.
                        if (!allowWriteTools && !ToolSafetyPolicy.isReadOnly(candidate)) {
                            excludedWrites.add(tool.name());
                            continue;
                        }
                        candidates.add(candidate);
                        log.debug("MCP tool catalog entry added: serverId={}, toolName={}, schemaProperties={}",
                                serverId, tool.name(), schema.get("properties") instanceof Map<?, ?> props ? props.size() : 0);
                    }
                }
                String next = page.nextCursor();
                if (next == null || next.isBlank() || next.equals(cursor)) break;
                cursor = next;
            } while (true);
            if (!excludedWrites.isEmpty()) {
                log.info("MCP catalog excluded {} tool(s) that may change data (connectai.agent.allow-write-tools=false): serverId={}, tools={}",
                        excludedWrites.size(), serverId, excludedWrites);
            }
        } catch (RuntimeException exception) {
            // A single unavailable MCP server must not hide tools from other servers.
            log.error("MCP tool listing failed; server contributes no further tools: serverId={}, cause={}",
                    serverId, exception.toString(), exception);
        }
    }
}
