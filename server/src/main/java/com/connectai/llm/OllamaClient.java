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
 * Local Ollama implementation of the existing provider-neutral LLM interface.
 */
public final class OllamaClient implements LlmClient {

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaClient(
            String baseUrl,
            String model,
            Duration timeout,
            ObjectMapper mapper) {

        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.timeout = timeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    @Override
    public JsonNode callTool(
            String system,
            String user,
            ToolSpec tool) {

        JsonNode spec = mapper.valueToTree(tool);

        String name = firstText(
                spec,
                "name",
                "toolName",
                "tool_name");

        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "Cannot call Ollama tool: ToolSpec has no serialized name field.");
        }

        String description = firstText(
                spec,
                "description",
                "toolDescription",
                "tool_description");

        JsonNode parameters = firstNode(
                spec,
                "inputSchema",
                "input_schema",
                "parameters",
                "schema");

        if (parameters == null || !parameters.isObject()) {
            ObjectNode emptySchema = mapper.createObjectNode();
            emptySchema.put("type", "object");
            emptySchema.set(
                    "properties",
                    mapper.createObjectNode());

            parameters = emptySchema;
        }

        ObjectNode function = mapper.createObjectNode();
        function.put("name", name);

        if (description != null) {
            function.put("description", description);
        }

        function.set("parameters", parameters);

        ObjectNode toolDefinition = mapper.createObjectNode();
        toolDefinition.put("type", "function");
        toolDefinition.set("function", function);

        ObjectNode requestBody = baseRequest(system, user);

        // Request JSON output from Ollama.
        requestBody.put("format", "json");

        ArrayNode tools = requestBody.putArray("tools");
        tools.add(toolDefinition);

        JsonNode response = postChat(requestBody);

        /*
         * First, handle Ollama's structured tool-call response.
         */
        JsonNode calls = response
                .path("message")
                .path("tool_calls");

        if (calls.isArray() && !calls.isEmpty()) {
            JsonNode arguments = calls
                    .get(0)
                    .path("function")
                    .path("arguments");

            if (arguments.isObject()) {
                return arguments;
            }

            if (arguments.isTextual()) {
                return parseJson(
                        arguments.asText(),
                        "Ollama tool arguments");
            }

            if (arguments.isArray()) {
                throw new IllegalStateException(
                        "Ollama returned an array instead of an object "
                                + "for arguments of tool '" + name + "'.");
            }
        }

        /*
         * Some models return the requested JSON in message.content
         * rather than in the structured tool_calls field.
         */
        String content = response
                .path("message")
                .path("content")
                .asText("")
                .trim();

        if (!content.isEmpty()) {
            return parseJson(
                    content,
                    "Ollama tool-call response");
        }

        throw new IllegalStateException(
                "Ollama returned neither tool arguments nor JSON content "
                        + "for tool '" + name + "'.");
    }

    @Override
    public String complete(String system, String user) {
        JsonNode response = postChat(
                baseRequest(system, user));

        String content = response
                .path("message")
                .path("content")
                .asText("");

        if (content.isBlank()) {
            throw new IllegalStateException(
                    "Ollama returned an empty completion for model '"
                            + model + "'.");
        }

        return content;
    }

    /**
     * Builds the Ollama chat request.
     */
    private ObjectNode baseRequest(
            String system,
            String user) {

        ObjectNode body = mapper.createObjectNode();

        body.put("model", model);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put(
                "content",
                system == null ? "" : system);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put(
                "content",
                user == null ? "" : user);

        return body;
    }

    /**
     * Sends a request to Ollama's chat API.
     */
    private JsonNode postChat(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header(
                            "Content-Type",
                            "application/json")
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                throw new IllegalStateException(
                        "Ollama HTTP " + response.statusCode()
                                + ": "
                                + abbreviate(response.body(), 800));
            }

            return mapper.readTree(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                    "Interrupted while waiting for Ollama at "
                            + baseUrl,
                    e);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not communicate with Ollama at "
                            + baseUrl
                            + ". Confirm Ollama is running and the model is available.",
                    e);
        }
    }

    /**
     * Parses JSON returned by Ollama.
     *
     * Handles common Markdown fences and Qwen reasoning tags.
     * It does not attempt to infer or fabricate missing JSON.
     */
    private JsonNode parseJson(
            String value,
            String context) {

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    context + " was empty.");
        }

        String json = value.trim();

        // Remove complete Qwen reasoning blocks.
        json = json.replaceAll(
                "(?s)<think>.*?</think>",
                "").trim();

        // Remove an opening Markdown fence and its closing fence.
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");

            if (firstNewline >= 0
                    && lastFence > firstNewline) {

                json = json.substring(
                        firstNewline + 1,
                        lastFence).trim();
            }
        }

        try {
            JsonNode parsed = mapper.readTree(json);

            if (parsed == null || parsed.isMissingNode()) {
                throw new IllegalStateException(
                        context + " did not contain a JSON value.");
            }

            return parsed;

        } catch (IOException e) {
            throw new IllegalStateException(
                    context
                            + " was not valid JSON. Response excerpt: "
                            + abbreviate(json, 500),
                    e);
        }
    }

    private static String firstText(
            JsonNode node,
            String... fields) {

        for (String field : fields) {
            JsonNode value = node.get(field);

            if (value != null
                    && value.isValueNode()
                    && !value.asText().isBlank()) {

                return value.asText();
            }
        }

        return null;
    }

    private static JsonNode firstNode(
            JsonNode node,
            String... fields) {

        for (String field : fields) {
            JsonNode value = node.get(field);

            if (value != null && !value.isNull()) {
                return value;
            }
        }

        return null;
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:11434";
        }

        String result = value.trim();

        while (result.endsWith("/")) {
            result = result.substring(
                    0,
                    result.length() - 1);
        }

        return result;
    }

    private static String abbreviate(
            String value,
            int max) {

        if (value == null) {
            return "";
        }

        return value.length() <= max
                ? value
                : value.substring(0, max) + "...";
    }
}