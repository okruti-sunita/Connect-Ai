package com.connectai.mcp.client;

import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolExecutionPort;
import com.connectai.agent.ToolExecutionRequest;
import com.connectai.agent.ToolExecutionResult;
import com.connectai.mcp.api.dto.McpToolExecutionRequest;
import com.connectai.mcp.api.dto.McpToolExecutionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP implementation of the agent's transport-neutral tool execution port.
 *
 * The adapter is the only agent-facing class that knows MCP DTOs. It normalises every
 * MCP content block into text the agent can reason about, without inventing anything:
 * text is kept (bounded), embedded text resources are kept, and binary blocks (images,
 * audio, blobs) are summarised by type and size rather than dumped as base64.
 */
@Component
@Slf4j
public class McpToolExecutionAdapter implements ToolExecutionPort {

    static final int MAX_TEXT_CHARS = 20_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpToolExecutionService executionService;

    public McpToolExecutionAdapter(McpToolExecutionService executionService) {
        this.executionService = executionService;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        McpToolExecutionResponse response = executionService.execute(
                request.serverId(),
                request.toolName(),
                new McpToolExecutionRequest(request.arguments()));

        List<ToolExecutionContent> content = toContent(response.content());
        log.debug("Agent-to-MCP adapter received response: toolName={}, isError={}, contentItems={}",
                response.toolName(), response.isError(), content.size());

        return new ToolExecutionResult(
                response.toolName(),
                response.isError(),
                content,
                response.structuredContent());
    }

    private List<ToolExecutionContent> toContent(List<McpSchema.Content> content) {
        if (content == null) {
            return List.of();
        }
        return content.stream()
                .map(McpToolExecutionAdapter::toContent)
                .toList();
    }

    static ToolExecutionContent toContent(McpSchema.Content content) {
        if (content == null) {
            return new ToolExecutionContent("unknown", null);
        }
        if (content instanceof McpSchema.TextContent text) {
            return new ToolExecutionContent("text", bound(text.text()));
        }
        if (content instanceof McpSchema.ImageContent image) {
            return new ToolExecutionContent("image", "[image " + image.mimeType()
                    + ", " + (image.data() == null ? 0 : image.data().length()) + " base64 chars omitted]");
        }
        if (content instanceof McpSchema.AudioContent audio) {
            return new ToolExecutionContent("audio", "[audio " + audio.mimeType() + " omitted]");
        }
        if (content instanceof McpSchema.EmbeddedResource embedded) {
            if (embedded.resource() instanceof McpSchema.TextResourceContents textResource) {
                return new ToolExecutionContent("resource",
                        "[" + textResource.uri() + "]\n" + bound(textResource.text()));
            }
            return new ToolExecutionContent("resource",
                    "[binary resource " + embedded.resource().uri() + " omitted]");
        }
        // Resource links and future content types: keep their JSON form, bounded.
        try {
            Map<?, ?> asMap = MAPPER.convertValue(content, Map.class);
            return new ToolExecutionContent(content.type(), bound(MAPPER.writeValueAsString(asMap)));
        } catch (Exception exception) {
            return new ToolExecutionContent(content.type(), null);
        }
    }

    private static String bound(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TEXT_CHARS) + "\n... [truncated " + (text.length() - MAX_TEXT_CHARS) + " chars]";
    }
}
