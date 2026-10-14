package com.connectai.llm;

import com.connectai.agent.McpToolCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PgVectorToolRetrieverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void embedsChangedLiveMetadataAndReturnsOnlyRankedLiveTools() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpToolCandidate live = candidate(UUID.randomUUID(), "query_incident_history");
        McpToolCandidate withCredentialLikeMetadata = new McpToolCandidate(live.serverId(), live.toolName(),
                live.title(), "Tool metadata token=private-should-not-leak", live.inputSchema(),
                live.readOnlyHint(), live.serverName());
        AtomicInteger embedRequests = new AtomicInteger();
        AtomicReference<String> documentRequest = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            int requestNumber = embedRequests.incrementAndGet();
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            JsonNode payload = mapper.readTree(requestBytes);
            JsonNode input = payload.path("input");
            if (input.isArray() && input.size() > 0 && input.get(0).asText().contains("Tool: ")) {
                documentRequest.set(new String(requestBytes, StandardCharsets.UTF_8));
            }
            int count = input.isArray() ? input.size() : 1;
            StringBuilder response = new StringBuilder("{\"embeddings\":[");
            for (int i = 0; i < count; i++) {
                if (i > 0) response.append(',');
                response.append("[0.25,0.75]");
            }
            byte[] bytes = response.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            doAnswer(invocation -> null).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
            doReturn(new int[]{1}).when(jdbc).batchUpdate(anyString(), any(List.class));
            doAnswer(invocation -> List.of(withCredentialLikeMetadata)).when(jdbc)
                    .query(anyString(), any(RowMapper.class), any(Object[].class));

            PgVectorToolRetriever retriever = new PgVectorToolRetriever(jdbc, mapper, true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-embed", 3);
            List<McpToolCandidate> results = retriever.retrieve(
                    "What caused incidents?", List.of(withCredentialLikeMetadata), 4);

            assertThat(results).containsExactly(withCredentialLikeMetadata);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(sql.capture(), any(RowMapper.class), parameters.capture());
            assertThat(sql.getValue()).contains("(e.embedding <=> q.embedding) <= ?")
                    .contains("ORDER BY e.embedding <=> q.embedding");
            assertThat(parameters.getValue()).contains(0.6d);
            assertThat(embedRequests).hasValue(2); // query and changed document
            assertThat(documentRequest.get()).contains("token=[REDACTED]")
                    .doesNotContain("private-should-not-leak");
            verify(jdbc).batchUpdate(anyString(), any(List.class));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsWeakNearestNeighborUsingConfiguredMaximumCosineDistance() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpToolCandidate live = candidate(UUID.randomUUID(), "query_incident_history");
        AtomicReference<String> retrievalSql = new AtomicReference<>();
        HttpServer server = embeddingServer();
        server.start();
        try {
            doAnswer(invocation -> null).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
            doReturn(new int[]{1}).when(jdbc).batchUpdate(anyString(), any(List.class));
            doAnswer(invocation -> {
                retrievalSql.set(invocation.getArgument(0));
                // Model the database returning no rows for a neighbor beyond the configured SQL distance cutoff.
                return List.of();
            }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

            PgVectorToolRetriever retriever = new PgVectorToolRetriever(jdbc, mapper, true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-embed", 3, 0.4);
            assertThat(retriever.retrieve("What caused the incidents?", List.of(live), 4)).isEmpty();

            assertThat(retrievalSql.get()).contains("(e.embedding <=> q.embedding) <= ?");
            ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).query(eq(retrievalSql.get()), any(RowMapper.class), parameters.capture());
            assertThat(parameters.getValue()).contains(0.4d);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOutOfRangeOrNonFiniteMaximumDistance() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PgVectorToolRetriever(jdbc, mapper, true,
                "http://127.0.0.1:1", "test-embed", 1, 2.1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PgVectorToolRetriever(jdbc, mapper, true,
                "http://127.0.0.1:1", "test-embed", 1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpServer embeddingServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            JsonNode payload = mapper.readTree(exchange.getRequestBody());
            int count = payload.path("input").size();
            StringBuilder response = new StringBuilder("{\"embeddings\":[");
            for (int i = 0; i < count; i++) {
                if (i > 0) response.append(',');
                response.append("[0.25,0.75]");
            }
            byte[] bytes = response.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    @Test
    void disabledRetrieverDoesNotCallDatabaseOrEmbeddingService() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PgVectorToolRetriever retriever = new PgVectorToolRetriever(jdbc, mapper, false,
                "http://127.0.0.1:1", "test-embed", 1);

        assertThat(retriever.retrieve("question", List.of(candidate(UUID.randomUUID(), "search")), 3)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void malformedEmbeddingResponseFallsBackWithoutWritingAnIndexRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        McpToolCandidate live = candidate(UUID.randomUUID(), "query_incident_history");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] bytes = "{\"embeddings\":[[\"bad\"]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            doAnswer(invocation -> null).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
            PgVectorToolRetriever retriever = new PgVectorToolRetriever(jdbc, mapper, true,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-embed", 3);

            assertThat(retriever.retrieve("What caused incidents?", List.of(live), 4)).isEmpty();
            org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).batchUpdate(anyString(), any(List.class));
        } finally {
            server.stop(0);
        }
    }

    private static McpToolCandidate candidate(UUID serverId, String name) {
        return new McpToolCandidate(serverId, name, name, "Find incident history records",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))));
    }
}
