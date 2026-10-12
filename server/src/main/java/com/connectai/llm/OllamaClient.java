package com.connectai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Local Ollama implementation of the provider-neutral {@link LlmClient}.
 *
 * Structured decisions use Ollama's schema-constrained output ({@code format: <JSON schema>})
 * rather than OpenAI-style tool calling: small models such as qwen3:1.7b follow a grammar-
 * constrained schema far more reliably than they emit {@code tool_calls}. Thinking is
 * disabled ({@code think: false}) so no reasoning trace is generated, stored or parsed, and
 * the context window is set explicitly so prompts are never silently truncated by Ollama's
 * small default.
 *
 * Responses are still treated as untrusted text: think blocks and Markdown fences are
 * stripped, and the result must be exactly one JSON object. Semantic validation (allowed
 * actions, argument schemas) is the caller's job.
 */
public final class OllamaClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern UNCLOSED_THINK = Pattern.compile("(?is)^\\s*<think>.*");

    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final int numCtx;
    private final int maxOutputTokens;
    private final double temperature;

    public OllamaClient(String baseUrl, String model, Duration timeout, ObjectMapper mapper) {
        this(baseUrl, model, timeout, mapper, 8192, 1024, 0.0);
    }

    public OllamaClient(
            String baseUrl,
            String model,
            Duration timeout,
            ObjectMapper mapper,
            int numCtx,
            int maxOutputTokens,
            double temperature) {

        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("connectai.llm.ollama-model must be set");
        }
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model.trim();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.numCtx = numCtx;
        this.maxOutputTokens = maxOutputTokens;
        this.temperature = temperature;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, Math.max(1, timeout.toSeconds()))))
                .build();
    }

    public String model() {
        return model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public JsonNode callTool(String system, String user, ToolSpec tool) {
        Objects.requireNonNull(tool, "tool");

        ObjectNode body = baseRequest(system, user);
        JsonNode schema = tool.inputSchema() == null
                ? mapper.createObjectNode().put("type", "object")
                : mapper.valueToTree(tool.inputSchema());
        body.set("format", schema);

        long started = System.nanoTime();
        JsonNode response = postChat(body);
        log.debug("Ollama structured call finished: model={}, tool={}, elapsedMs={}, promptTokens={}, outputTokens={}",
                model, tool.name(), elapsedMs(started),
                response.path("prompt_eval_count").asInt(-1), response.path("eval_count").asInt(-1));

        return interpretStructuredResponse(response, mapper);
    }

    @Override
    public String complete(String system, String user) {
        long started = System.nanoTime();
        JsonNode response = postChat(baseRequest(system, user));
        log.debug("Ollama completion finished: model={}, elapsedMs={}", model, elapsedMs(started));

        String content = stripReasoning(response.path("message").path("content").asText(""));
        if (content.isBlank()) {
            throw new LlmException("Ollama returned an empty completion for model '" + model + "'.");
        }
        return content;
    }

    /**
     * Lists installed models, used by readiness checks. Throws {@link LlmException} when
     * Ollama is unreachable.
     */
    public List<String> installedModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmException("Ollama /api/tags returned HTTP " + response.statusCode());
            }
            List<String> names = new ArrayList<>();
            mapper.readTree(response.body()).path("models")
                    .forEach(m -> names.add(m.path("name").asText()));
            return names;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while contacting Ollama", e);
        } catch (IOException e) {
            throw new LlmException("Ollama is not reachable at " + baseUrl + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** True when {@link #model()} is installed; tolerates the implicit ":latest" tag. */
    public static boolean isInstalled(String model, List<String> installed) {
        String wanted = model.contains(":") ? model : model + ":latest";
        return installed.stream().anyMatch(name -> name.equals(model) || name.equals(wanted));
    }

    // ---------------------------------------------------------------- parsing (package-private for tests)

    /**
     * Extracts the single JSON object from a schema-constrained chat response. Accepts, in order:
     * a structured {@code tool_calls[0].function.arguments} object (some models still emit one),
     * then {@code message.content}. Anything else is rejected with a short, safe excerpt.
     */
    static JsonNode interpretStructuredResponse(JsonNode response, ObjectMapper mapper) {
        JsonNode message = response.path("message");

        JsonNode calls = message.path("tool_calls");
        if (calls.isArray() && !calls.isEmpty()) {
            JsonNode arguments = calls.get(0).path("function").path("arguments");
            if (arguments.isObject()) {
                return arguments;
            }
            if (arguments.isTextual()) {
                return parseJsonObject(arguments.asText(), mapper);
            }
        }

        String content = message.path("content").asText("");
        if ("length".equals(response.path("done_reason").asText())) {
            throw new LlmException("Ollama output was truncated at the token limit (done_reason=length). "
                    + "Response excerpt: " + excerpt(stripReasoning(content)));
        }
        return parseJsonObject(content, mapper);
    }

    /**
     * Parses exactly one JSON object out of model text. Strips think blocks and Markdown
     * fences; if prose surrounds the object, the first balanced top-level object is used.
     * The caller must still validate every field.
     */
    static JsonNode parseJsonObject(String raw, ObjectMapper mapper) {
        String text = stripReasoning(raw);
        if (text.isBlank()) {
            throw new LlmException("Model response was empty.");
        }

        text = stripFences(text);

        JsonNode parsed = tryParse(text, mapper);
        if (parsed == null) {
            String candidate = firstBalancedObject(text);
            parsed = candidate == null ? null : tryParse(candidate, mapper);
        }
        if (parsed == null) {
            throw new LlmException("Model response was not valid JSON. Response excerpt: " + excerpt(text));
        }
        if (!parsed.isObject()) {
            throw new LlmException("Model response was JSON but not an object. Response excerpt: " + excerpt(text));
        }
        return parsed;
    }

    static String stripReasoning(String raw) {
        if (raw == null) {
            return "";
        }
        String text = THINK_BLOCK.matcher(raw).replaceAll("");
        // An unclosed <think> means the model never produced an answer after reasoning.
        text = UNCLOSED_THINK.matcher(text).replaceAll("");
        return text.trim();
    }

    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed.replace("```", "").trim();
    }

    private static JsonNode tryParse(String text, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns the first brace-balanced object, honouring JSON string escapes; null if none. */
    private static String firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP

    private ObjectNode baseRequest(String system, String user) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("think", false);
        // Keep the model loaded between questions (Ollama unloads after 5 minutes by default).
        body.put("keep_alive", "30m");

        ObjectNode options = body.putObject("options");
        options.put("temperature", temperature);
        options.put("num_ctx", numCtx);
        options.put("num_predict", maxOutputTokens);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", system == null ? "" : system);
        messages.addObject().put("role", "user").put("content", user == null ? "" : user);
        return body;
    }

    private JsonNode postChat(ObjectNode body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 404 && response.body() != null && response.body().contains("not found")) {
                throw new LlmException("Ollama model '" + model + "' is not installed. Run: ollama pull " + model);
            }
            if (status < 200 || status >= 300) {
                throw new LlmException("Ollama HTTP " + status + ": " + excerpt(response.body()));
            }

            JsonNode parsed = mapper.readTree(response.body());
            if (parsed.hasNonNull("error")) {
                throw new LlmException("Ollama error: " + excerpt(parsed.path("error").asText()));
            }
            return parsed;

        } catch (HttpTimeoutException e) {
            throw new LlmException("Ollama did not answer within " + timeout.toSeconds()
                    + "s (model '" + model + "'). Increase connectai.llm.timeout-seconds or use a smaller model.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted while waiting for Ollama", e);
        } catch (IOException e) {
            throw new LlmException("Could not reach Ollama at " + baseUrl
                    + ". Start it with 'ollama serve' and confirm the model is installed.", e);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String excerpt(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:11434";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
