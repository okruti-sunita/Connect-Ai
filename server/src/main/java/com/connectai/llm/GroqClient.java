
package com.connectai.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class GroqClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final Duration timeout;
    private final double temperature;

    public GroqClient(String baseUrl, String apiKey, String model,
                      int maxTokens, Duration timeout, double temperature,
                      ObjectMapper mapper) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing GROQ_API_KEY environment variable.");
        }

        this.apiKey = apiKey.trim();
        this.model = Objects.requireNonNull(model);
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.temperature = temperature;
        this.mapper = mapper;

        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.groq.com/openai/v1"
                : baseUrl.trim().replaceAll("/+$", "");

        this.endpoint = URI.create(base + "/chat/completions");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String complete(String system, String user) {
        JsonNode response = send(baseRequest(system, user));
        String result = response.path("choices").path(0)
                .path("message").path("content").asText("").trim();

        if (result.isBlank()) {
            throw new LlmException("Groq returned an empty response.");
        }
        return result;
    }

    @Override
    public JsonNode callTool(String system, String user, ToolSpec tool) {
        String toolSystem = (system == null ? "" : system)
                + "\nReturn a JSON object matching the supplied response schema for "
                + tool.name() + ". "
                + (tool.description() == null ? "" : tool.description());
        ObjectNode body = baseRequest(toolSystem, user);

        JsonNode schema = tool.inputSchema() == null
                ? mapper.createObjectNode().put("type", "object")
                : mapper.valueToTree(tool.inputSchema());

        ObjectNode responseFormat = body.putObject("response_format");
        responseFormat.put("type", "json_schema");
        ObjectNode jsonSchema = responseFormat.putObject("json_schema");
        jsonSchema.put("name", tool.name());
        jsonSchema.put("strict", false);
        jsonSchema.set("schema", schema);

        JsonNode response = send(body);
        JsonNode calls = response.path("choices").path(0)
                .path("message").path("tool_calls");

        if (calls.isArray()) {
            for (JsonNode call : calls) {
                JsonNode fn = call.path("function");

                if (tool.name().equals(fn.path("name").asText())) {
                    JsonNode args = fn.path("arguments");

                    if (args.isObject()) {
                        return args;
                    }
                    if (args.isTextual()) {
                        return OllamaClient.parseJsonObject(
                                args.asText(), mapper);
                    }
                }
            }
        }

        String content = response.path("choices").path(0)
                .path("message").path("content").asText("");

        if (!content.isBlank()) {
            return OllamaClient.parseJsonObject(content, mapper);
        }

        throw new LlmException(
                "Groq did not return JSON arguments for '"
                        + tool.name() + "'.");
    }

    private ObjectNode baseRequest(String system, String user) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_completion_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", system == null ? "" : system);
        messages.addObject()
                .put("role", "user")
                .put("content", user == null ? "" : user);

        return body;
    }

    private JsonNode send(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {
                Long retryAfter = response.headers().firstValue("Retry-After")
                        .flatMap(GroqClient::parseRetryAfterSeconds)
                        .orElse(null);
                throw new LlmProviderException(response.statusCode(), retryAfter);
            }

            JsonNode parsed = mapper.readTree(response.body());

            if (parsed == null || !parsed.path("choices").isArray()
                    || parsed.path("choices").isEmpty()) {
                throw new LlmException(
                        "Groq response did not contain any choices.");
            }

            return parsed;
        } catch (HttpTimeoutException e) {
            throw new LlmException("Groq request timed out.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Groq request interrupted.", e);
        } catch (JsonProcessingException e) {
            throw new LlmException("Groq response was not valid JSON.", e);
        } catch (IOException e) {
            throw new LlmException(
                    "Could not communicate with Groq: "
                            + e.getClass().getSimpleName(), e);
        }
    }

    private static java.util.Optional<Long> parseRetryAfterSeconds(String value) {
        if (value == null || value.isBlank()) return java.util.Optional.empty();
        String trimmed = value.trim();
        try {
            if (trimmed.matches("\\d+")) return java.util.Optional.of(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
        try {
            long seconds = Duration.between(Instant.now(), ZonedDateTime.parse(
                    trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            return java.util.Optional.of(Math.max(0, seconds));
        } catch (DateTimeParseException ignored) {
            return java.util.Optional.empty();
        }
    }
}
