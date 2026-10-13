package com.connectai.mcp.agent;

import com.connectai.agent.AgentToolCatalog;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ToolCatalogSnapshot;
import com.connectai.agent.ToolSafetyPolicy;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Infrastructure adapter exposing tools from enabled, connected MCP servers. */
@Component
@Slf4j
public class McpAgentToolCatalog implements AgentToolCatalog {
    private static final int MAX_TOOL_PAGES_PER_SERVER = 100;
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
        return snapshot().tools();
    }

    @Override
    public ToolCatalogSnapshot snapshot() {
        var connectedIds = clientRegistry.connectedServerIds();
        log.info("MCP catalog build started: connectedRuntimeServers={}", connectedIds.size());
        List<McpToolCandidate> candidates = new ArrayList<>();
        List<UUID> failedScans = new ArrayList<>();
        for (UUID serverId : connectedIds) {
            int before = candidates.size();
            if (!collectServerTools(serverId, candidates)) {
                failedScans.add(serverId);
            }
            log.info("MCP catalog server scan finished: serverId={}, toolsAdded={}", serverId, candidates.size() - before);
        }
        if (candidates.isEmpty() && !failedScans.isEmpty()) {
            throw new IllegalStateException("MCP tool discovery failed for connected server(s) " + failedScans
                    + "; check the connection and tool-list endpoint.");
        }
        log.info("MCP catalog build finished: availableTools={}, toolIds={}", candidates.size(),
                candidates.stream().map(McpToolCandidate::plannerId).toList());
        List<String> warnings = failedScans.isEmpty() ? List.of()
                : List.of("MCP tool discovery failed on " + failedScans.size()
                        + " connected server(s); available tools may be incomplete.");
        return new ToolCatalogSnapshot(candidates, warnings);
    }

    private boolean collectServerTools(UUID serverId, List<McpToolCandidate> candidates) {
        var server = serverRepository.findById(serverId).orElse(null);
        if (server == null) {
            log.warn("MCP catalog skipped runtime server because database row is missing: serverId={}", serverId);
            return false;
        }
        if (!server.isEnabled() || server.getStatus() != McpConnectionStatus.CONNECTED) {
            log.warn("MCP catalog skipped server: serverId={}, enabled={}, persistedStatus={}",
                    serverId, server.isEnabled(), server.getStatus());
            return false;
        }
        var client = clientRegistry.find(serverId).orElse(null);
        if (client == null) {
            log.warn("MCP catalog skipped server because runtime client is missing: serverId={}", serverId);
            return false;
        }
        log.debug("MCP catalog listing tools: serverId={}, serverName={}", serverId, server.getName());
        try {
            String cursor = null;
            Set<String> seenCursors = new HashSet<>();
            java.util.Set<String> seenNames = new java.util.HashSet<>();
            List<String> excludedWrites = new ArrayList<>();
            int pages = 0;
            do {
                if (++pages > MAX_TOOL_PAGES_PER_SERVER) {
                    log.error("MCP catalog pagination exceeded the page limit; stopping server scan: serverId={}", serverId);
                    throw new IllegalStateException("MCP listTools exceeded the maximum page count");
                }
                var page = client.listTools(cursor);
                if (page == null) {
                    log.warn("MCP listTools returned null page: serverId={}", serverId);
                    throw new IllegalStateException("MCP listTools returned a null page");
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
                if (next == null || next.isBlank()) break;
                if (!seenCursors.add(next)) {
                    log.error("MCP catalog pagination encountered a repeated cursor; stopping server scan: serverId={}", serverId);
                    throw new IllegalStateException("MCP listTools returned a repeated pagination cursor");
                }
                cursor = next;
            } while (true);
            if (!excludedWrites.isEmpty()) {
                log.info("MCP catalog excluded {} tool(s) that may change data (connectai.agent.allow-write-tools=false): serverId={}, tools={}",
                        excludedWrites.size(), serverId, excludedWrites);
            }
            return true;
        } catch (RuntimeException exception) {
            // A single unavailable MCP server must not hide tools from other servers.
            log.error("MCP tool listing failed; retaining tools collected before failure: serverId={}, causeType={}",
                    serverId, exception.getClass().getSimpleName());
            return false;
        }
    }
}
