package com.connectai.api;

import com.connectai.llm.LlmClient;
import com.connectai.llm.LlmException;
import com.connectai.llm.OllamaClient;
import com.connectai.mcp.client.McpClientRegistry;
import com.connectai.mcp.repository.McpServerRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Readiness diagnostics that keep the three dependencies apart: the backend itself, the
 * LLM (Ollama reachability and whether the configured model is installed) and each MCP
 * server (persisted status versus a live session in this process). Never returns secrets.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ObjectProvider<LlmClient> llmClient;
    private final McpServerRepository servers;
    private final McpClientRegistry registry;
    private final boolean llmEnabled;
    private final String provider;
    private final String toolsMode;

    public HealthController(
            ObjectProvider<LlmClient> llmClient,
            McpServerRepository servers,
            McpClientRegistry registry,
            @Value("${connectai.llm.enabled:false}") boolean llmEnabled,
            @Value("${connectai.llm.provider:anthropic}") String provider,
            @Value("${connectai.tools.mode:mcp}") String toolsMode) {
        this.llmClient = llmClient;
        this.servers = servers;
        this.registry = registry;
        this.llmEnabled = llmEnabled;
        this.provider = provider;
        this.toolsMode = toolsMode;
    }

    public record LlmHealth(boolean enabled, String provider, String model, Boolean reachable,
                            Boolean modelInstalled, String message) { }

    public record McpHealth(UUID id, String name, String persistedStatus, boolean live, String lastError) { }

    public record Health(String backend, String toolsMode, LlmHealth llm, List<McpHealth> mcpServers) { }

    @GetMapping
    @Transactional(readOnly = true)
    public Health health() {
        List<McpHealth> mcp = servers.findAll().stream()
                .map(server -> new McpHealth(
                        server.getId(),
                        server.getName(),
                        String.valueOf(server.getStatus()),
                        registry.isConnected(server.getId()),
                        server.getLastError()))
                .toList();
        return new Health("UP", toolsMode, llmHealth(), mcp);
    }

    private LlmHealth llmHealth() {
        if (!llmEnabled) {
            return new LlmHealth(false, provider, null, null, null,
                    "LLM disabled: rule-based planner and template answers are used.");
        }
        LlmClient client = llmClient.getIfAvailable();
        if (client instanceof OllamaClient ollama) {
            try {
                boolean installed = OllamaClient.isInstalled(ollama.model(), ollama.installedModels());
                return new LlmHealth(true, "ollama", ollama.model(), true, installed,
                        installed ? "Ollama reachable; model installed."
                                : "Model not installed. Run: ollama pull " + ollama.model());
            } catch (LlmException e) {
                return new LlmHealth(true, "ollama", ollama.model(), false, null, e.getMessage());
            }
        }
        return new LlmHealth(true, provider, null, null, null,
                client == null ? "No LLM client configured." : "Remote provider; not probed to avoid cost.");
    }
}
