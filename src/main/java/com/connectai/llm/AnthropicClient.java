package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the Anthropic Messages API using only the JDK's HttpClient (no extra dependency).
 * The API key is sent as a header and is never logged or included in exceptions.
 */
public class AnthropicClient implements LlmClient {

    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI messagesUri;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;

    public AnthropicClient(String baseUrl, String apiKey, String model, int maxTokens,
                           Duration timeout, ObjectMapper mapper) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.messagesUri = URI.create(base + "/v1/messages");
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public JsonNode callTool(String system, String user, ToolSpec tool) {
        ObjectNode body = baseRequest(system, user);

        ObjectNode toolNode = body.putArray("tools").addObject();
        toolNode.put("name", tool.name());
        toolNode.put("description", tool.description());
        toolNode.set("input_schema", mapper.valueToTree(tool.inputSchema()));

        // Forcing the tool guarantees a structured answer instead of free text.
        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", tool.name());

        JsonNode response = send(body);
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())
                    && tool.name().equals(block.path("name").asText())) {
                return block.path("input");
            }
        }
        throw new LlmException("Model did not call the requested tool '" + tool.name() + "'");
    }

    @Override
    public String complete(String system, String user) {
        JsonNode response = send(baseRequest(system, user));
        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        if (text.isEmpty()) {
            throw new LlmException("Model returned no text");
        }
        return text.toString().trim();
    }

    private ObjectNode baseRequest(String system, String user) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        ArrayNode messages = body.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", user);
        return body;
    }

    private JsonNode send(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(messagesUri)
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new LlmException("LLM API returned HTTP " + response.statusCode() + ": "
                        + PromptText.abbreviate(response.body(), 300));
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new LlmException("LLM call failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM call interrupted", e);
        }
    }
}
