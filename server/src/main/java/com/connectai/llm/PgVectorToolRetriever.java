package com.connectai.llm;

import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.SafeDiagnostic;
import com.connectai.agent.ToolSemanticRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Optional live-tool semantic index backed by PostgreSQL/pgvector and Ollama's /api/embed API.
 * The live MCP catalog remains authoritative: vector results are intersected with the
 * currently eligible candidates before they can reach the planner.
 */
@Component
public class PgVectorToolRetriever implements ToolSemanticRetriever {

    private static final Logger log = LoggerFactory.getLogger(PgVectorToolRetriever.class);
    private static final int MAX_LIVE_TOOLS = 500;
    private static final int EMBED_BATCH_SIZE = 32;
    private static final int MAX_DOCUMENT_CHARS = 4_000;
    private static final int MAX_DIMENSIONS = 8_192;
    private static final int MAX_EMBED_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final String EMBEDDING_VERSION = "tool-metadata-v1";
    static final double DEFAULT_MAX_COSINE_DISTANCE = 0.6;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final double maxCosineDistance;
    private final HttpClient http;

    @Autowired
    public PgVectorToolRetriever(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${connectai.agent.semantic.enabled:false}") boolean enabled,
            @Value("${connectai.agent.semantic.ollama-base-url:${connectai.llm.ollama-base-url:http://localhost:11434}") String baseUrl,
            @Value("${connectai.agent.semantic.embedding-model:nomic-embed-text}") String model,
            @Value("${connectai.agent.semantic.timeout-seconds:10}") long timeoutSeconds,
            @Value("${connectai.agent.semantic.max-cosine-distance:0.6}") double maxCosineDistance) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(Objects.requireNonNull(baseUrl, "baseUrl"));
        this.model = Objects.requireNonNull(model, "model").trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        if (!Double.isFinite(maxCosineDistance) || maxCosineDistance < 0 || maxCosineDistance > 2) {
            throw new IllegalArgumentException("semantic max cosine distance must be between 0 and 2");
        }
        this.maxCosineDistance = maxCosineDistance;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public PgVectorToolRetriever(
            JdbcTemplate jdbc, ObjectMapper mapper, boolean enabled, String baseUrl, String model, long timeoutSeconds) {
        this(jdbc, mapper, enabled, baseUrl, model, timeoutSeconds, DEFAULT_MAX_COSINE_DISTANCE);
    }

    @Override
    public List<McpToolCandidate> retrieve(String question, List<McpToolCandidate> liveTools, int limit) {
        if (!enabled || question == null || question.isBlank() || liveTools == null || liveTools.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (liveTools.size() > MAX_LIVE_TOOLS) {
            log.warn("Semantic tool retrieval skipped: live tool catalog exceeds bounded limit {}", MAX_LIVE_TOOLS);
            return List.of();
        }
        if (model.isBlank()) {
            log.warn("Semantic tool retrieval skipped: embedding model is not configured");
            return List.of();
        }

        try {
            List<Double> queryVector = embed(List.of(question)).get(0);
            indexChangedTools(liveTools, queryVector.size());
            return search(queryVector, liveTools, limit);
        } catch (DataAccessException exception) {
            log.warn("Semantic tool retrieval unavailable; using lexical matching (database/pgvector unavailable)");
            return List.of();
        } catch (RuntimeException | java.io.IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Semantic tool retrieval unavailable; using lexical matching ({}): {}",
                    exception.getClass().getSimpleName(), SafeDiagnostic.message(exception, 240));
            return List.of();
        }
    }

    private void indexChangedTools(List<McpToolCandidate> tools, int expectedDimensions)
            throws java.io.IOException, InterruptedException {
        Map<String, StoredEmbedding> knownEmbeddings = new HashMap<>();
        StringBuilder liveValues = new StringBuilder();
        List<Object> liveParameters = new ArrayList<>();
        for (McpToolCandidate tool : tools) {
            if (!liveValues.isEmpty()) liveValues.append(", ");
            liveValues.append("(CAST(? AS UUID), ?)");
            liveParameters.add(tool.serverId());
            liveParameters.add(tool.toolName());
        }
        liveParameters.add(model);
        liveParameters.add(EMBEDDING_VERSION);
        jdbc.query("""
                        WITH live_tools(server_id, tool_name) AS (VALUES %s)
                        SELECT e.server_id, e.tool_name, e.content_hash, e.embedding_dimension
                        FROM mcp_tool_embedding e JOIN live_tools t
                          ON t.server_id = e.server_id AND t.tool_name = e.tool_name
                        WHERE e.embedding_model = ? AND e.embedding_version = ?
                        """.formatted(liveValues),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> knownEmbeddings.put(
                        key(rs.getObject("server_id", UUID.class), rs.getString("tool_name")),
                        new StoredEmbedding(rs.getString("content_hash"), rs.getInt("embedding_dimension"))),
                liveParameters.toArray());

        List<Document> changed = new ArrayList<>();
        for (McpToolCandidate tool : tools) {
            String content = document(tool);
            String hash = sha256(EMBEDDING_VERSION + "\n" + content);
            StoredEmbedding known = knownEmbeddings.get(key(tool.serverId(), tool.toolName()));
            if (known == null || !hash.equals(known.contentHash()) || known.dimensions() != expectedDimensions) {
                changed.add(new Document(tool, content, hash));
            }
        }

        for (int offset = 0; offset < changed.size(); offset += EMBED_BATCH_SIZE) {
            List<Document> batch = changed.subList(offset, Math.min(changed.size(), offset + EMBED_BATCH_SIZE));
            List<List<Double>> vectors = embed(batch.stream().map(Document::text).toList());
            int dimensions = vectors.get(0).size();
            if (dimensions != expectedDimensions) {
                throw new IllegalStateException("Embedding model returned inconsistent query and tool vector dimensions");
            }
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                List<Double> vector = vectors.get(i);
                if (vector.size() != dimensions) {
                    throw new IllegalStateException("Embedding model returned inconsistent vector dimensions");
                }
                Document document = batch.get(i);
                rows.add(new Object[]{document.tool().serverId(), document.tool().toolName(), model,
                        EMBEDDING_VERSION, dimensions, document.hash(), vectorLiteral(vector)});
            }
            jdbc.batchUpdate("""
                    INSERT INTO mcp_tool_embedding
                        (server_id, tool_name, embedding_model, embedding_version, embedding_dimension, content_hash, embedding, indexed_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), CURRENT_TIMESTAMP)
                    ON CONFLICT (server_id, tool_name, embedding_model) DO UPDATE SET
                        embedding_dimension = EXCLUDED.embedding_dimension,
                        embedding_version = EXCLUDED.embedding_version,
                        content_hash = EXCLUDED.content_hash,
                        embedding = EXCLUDED.embedding,
                        indexed_at = CURRENT_TIMESTAMP
                    """, rows);
        }
    }

    private List<McpToolCandidate> search(
            List<Double> queryVector, List<McpToolCandidate> liveTools, int limit) {
        if (queryVector.isEmpty()) {
            return List.of();
        }
        StringBuilder values = new StringBuilder();
        List<Object> pairParameters = new ArrayList<>();
        for (McpToolCandidate tool : liveTools) {
            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(CAST(? AS UUID), ?)");
            pairParameters.add(tool.serverId());
            pairParameters.add(tool.toolName());
        }
        String sql = """
                WITH live_tools(server_id, tool_name) AS (VALUES %s),
                query_embedding AS (SELECT CAST(? AS vector) AS embedding)
                SELECT e.server_id, e.tool_name
                FROM live_tools t
                JOIN mcp_tool_embedding e ON e.server_id = t.server_id AND e.tool_name = t.tool_name
                CROSS JOIN query_embedding q
                WHERE e.embedding_model = ? AND e.embedding_version = ? AND e.embedding_dimension = ?
                  AND (e.embedding <=> q.embedding) <= ?
                ORDER BY e.embedding <=> q.embedding
                LIMIT ?
                """.formatted(values);
        int pairParameterCount = liveTools.size() * 2;
        Object[] args = new Object[pairParameterCount + 6];
        System.arraycopy(pairParameters.toArray(), 0, args, 0, pairParameterCount);
        args[pairParameterCount] = vectorLiteral(queryVector);
        args[pairParameterCount + 1] = model;
        args[pairParameterCount + 2] = EMBEDDING_VERSION;
        args[pairParameterCount + 3] = queryVector.size();
        args[pairParameterCount + 4] = maxCosineDistance;
        args[pairParameterCount + 5] = Math.min(limit, liveTools.size());

        Map<String, McpToolCandidate> authoritative = new LinkedHashMap<>();
        liveTools.forEach(tool -> authoritative.put(key(tool.serverId(), tool.toolName()), tool));
        List<McpToolCandidate> result = jdbc.query(sql, (rs, row) -> authoritative.get(
                key(rs.getObject("server_id", UUID.class), rs.getString("tool_name"))), args);
        return result.stream().filter(Objects::nonNull).toList();
    }

    private List<List<Double>> embed(List<String> inputs) throws java.io.IOException, InterruptedException {
        if (inputs.isEmpty()) {
            return List.of();
        }
        byte[] payload = mapper.writeValueAsBytes(Map.of("model", model, "input", inputs, "truncate", false));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("Embedding service returned HTTP " + response.statusCode());
        }
        byte[] responseBytes;
        try (java.io.InputStream body = response.body()) {
            responseBytes = body.readNBytes(MAX_EMBED_RESPONSE_BYTES + 1);
        }
        if (responseBytes.length > MAX_EMBED_RESPONSE_BYTES) {
            throw new IllegalStateException("Embedding service response exceeded its size limit");
        }
        JsonNode embeddings = mapper.readTree(responseBytes).path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != inputs.size()) {
            throw new IllegalStateException("Embedding service returned a malformed embedding batch");
        }
        List<List<Double>> vectors = new ArrayList<>();
        int expectedDimensions = -1;
        for (JsonNode embedding : embeddings) {
            if (!embedding.isArray() || embedding.isEmpty() || embedding.size() > MAX_DIMENSIONS) {
                throw new IllegalStateException("Embedding service returned an invalid vector dimension");
            }
            if (expectedDimensions == -1) {
                expectedDimensions = embedding.size();
            } else if (expectedDimensions != embedding.size()) {
                throw new IllegalStateException("Embedding service returned inconsistent vector dimensions");
            }
            List<Double> vector = new ArrayList<>(embedding.size());
            for (JsonNode number : embedding) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble())) {
                    throw new IllegalStateException("Embedding service returned a non-numeric vector value");
                }
                vector.add(number.asDouble());
            }
            vectors.add(List.copyOf(vector));
        }
        return List.copyOf(vectors);
    }

    private String document(McpToolCandidate tool) {
        StringBuilder text = new StringBuilder();
        append(text, "Tool: ", tool.toolName());
        append(text, "Title: ", tool.title());
        append(text, "Purpose: ", tool.description());
        Object properties = tool.inputSchema().get("properties");
        if (properties instanceof Map<?, ?> map && !map.isEmpty()) {
            text.append("Input fields: ");
            map.keySet().stream().map(String::valueOf)
                    .filter(field -> !field.matches("(?i).*(token|secret|password|credential|authorization|api[-_]?key).*"))
                    .limit(40).forEach(field -> text.append(field).append(' '));
        }
        return SafeDiagnostic.sanitize(text.toString(), MAX_DOCUMENT_CHARS);
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append(value).append('\n');
        }
    }

    private static String vectorLiteral(List<Double> vector) {
        return vector.stream().map(value -> Double.toString(value)).reduce("[", (left, value) ->
                left.equals("[") ? left + value : left + "," + value) + "]";
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String key(UUID serverId, String toolName) {
        return serverId + "::" + toolName;
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record Document(McpToolCandidate tool, String text, String hash) {
    }

    private record StoredEmbedding(String contentHash, int dimensions) {
    }
}
