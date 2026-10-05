package com.connectai.mcp;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpConnectionStatus;
import com.connectai.mcp.repository.McpServerRepository;
import com.connectai.mcp.security.SecretEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "connectai.seed-demo-data=false")
@AutoConfigureMockMvc
class McpToolResourceTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private McpServerRepository repository;

    @Autowired
    private SecretEncryptionService encryptionService;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void registerGitHubMcpServer_returns201AndDoesNotExposeSecret() throws Exception {

        String token = "github-test-token";

        mvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "GitHub",
                                  "transport": "STREAMABLE_HTTP",
                                  "endpoint": "https://api.githubcopilot.com/mcp/",
                                  "authType": "BEARER",
                                  "secret": "%s",
                                  "enabled": true
                                }
                                """.formatted(token)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("GitHub"))
                .andExpect(jsonPath("$.transport").value("STREAMABLE_HTTP"))
                .andExpect(jsonPath("$.authType").value("BEARER"))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.secret").doesNotExist());

        EMcpServer saved = repository.findAll().getFirst();

        assertNotNull(saved.getEncryptedSecret());

        assertEquals(
                token,
                encryptionService.decrypt(saved.getEncryptedSecret())
        );

        assertEquals(
                McpConnectionStatus.REGISTERED,
                saved.getStatus()
        );
    }

    @Test
    void getTools_returnsRegisteredMcpServersWithoutSecrets() throws Exception {

        mvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "GitHub",
                                  "transport": "STREAMABLE_HTTP",
                                  "endpoint": "https://api.githubcopilot.com/mcp/",
                                  "authType": "BEARER",
                                  "secret": "github-test-token",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("GitHub"))
                .andExpect(jsonPath("$[0].status").value("REGISTERED"))
                .andExpect(jsonPath("$[0].secret").doesNotExist());
    }

    @Test
    void getTool_unknownId_returns404() throws Exception {

        mvc.perform(get("/api/tools/999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerTool_missingRequiredFields_returns400() throws Exception {

        mvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerTool_withoutSecret_doesNotStoreSecret() throws Exception {

        mvc.perform(post("/api/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Public MCP",
                                  "transport": "STREAMABLE_HTTP",
                                  "endpoint": "http://localhost:8000/mcp",
                                  "authType": "NONE",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").doesNotExist());

        EMcpServer saved = repository.findAll().getFirst();

        assertNull(saved.getEncryptedSecret());

        assertEquals(
                McpConnectionStatus.REGISTERED,
                saved.getStatus()
        );
    }
}