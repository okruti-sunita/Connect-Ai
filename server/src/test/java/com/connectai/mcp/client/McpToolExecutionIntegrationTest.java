package com.connectai.mcp.client;

import com.connectai.mcp.entity.EMcpServer;
import com.connectai.mcp.model.McpAuthType;
import com.connectai.mcp.model.McpTransportType;
import com.connectai.mcp.security.SecretEncryptionService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.Servlet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@Import(McpToolExecutionIntegrationTest.McpTestServerConfiguration.class)
// PER_CLASS lets @BeforeAll/@AfterAll be non-static instance methods, which is required
// here: @Autowired does not reach into static fields, so factory/mcpServer were never
// being populated by Spring - that's the direct cause of the NullPointerException.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolExecutionIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private McpSyncServer mcpServer;

    @Autowired
    private McpClientFactory factory;

    private McpSyncClient client;


    // ============================================================
    // SETUP
    // ============================================================

    @BeforeAll
    void beforeAll() {

        System.out.println();
        System.out.println("=================================================");
        System.out.println(" CONNECT AI - TASK 4");
        System.out.println(" REAL MCP TOOL EXECUTION INTEGRATION TEST");
        System.out.println("=================================================");
        System.out.println();

        System.out.println(
                "[TEST] MCP TEST SERVER CONFIGURATION LOADED"
        );
    }


    // ============================================================
    // CLEANUP
    // ============================================================

    @AfterAll
    void afterAll() {

        System.out.println();
        System.out.println("=================================================");
        System.out.println(" CLEANING UP TASK 4 TEST");
        System.out.println("=================================================");

        if (client != null) {

            try {

                client.closeGracefully();

                System.out.println(
                        "[TEST] MCP CLIENT CLOSED"
                );

            } catch (Exception exception) {

                System.out.println(
                        "[TEST] CLIENT CLOSE ERROR = "
                                + exception.getMessage()
                );
            }
        }

        if (mcpServer != null) {

            try {

                mcpServer.close();

                System.out.println(
                        "[TEST] MCP SERVER CLOSED"
                );

            } catch (Exception exception) {

                System.out.println(
                        "[TEST] SERVER CLOSE ERROR = "
                                + exception.getMessage()
                );
            }
        }

        System.out.println();
    }


    // ============================================================
    // TASK 4 MAIN TEST
    // ============================================================

    @Test
    void realSdkDiscoversAndExecutesToolOverStreamableHttp()
            throws Exception {

        System.out.println();
        System.out.println("=================================================");
        System.out.println(" STARTING TASK 4");
        System.out.println("=================================================");
        System.out.println();


        // ========================================================
        // STEP 1 - BUILD MCP ENDPOINT
        // ========================================================

        String endpoint =
                "http://localhost:"
                        + port
                        + "/mcp";

        System.out.println(
                "[TEST] STEP 1 -> MCP ENDPOINT"
        );

        System.out.println(
                "[TEST] ENDPOINT = "
                        + endpoint
        );


        // ========================================================
        // STEP 2 - CREATE SERVER CONFIG
        // ========================================================

        EMcpServer serverConfig =
                new EMcpServer(
                        "task-4-test-server",
                        McpTransportType.STREAMABLE_HTTP,
                        endpoint,
                        McpAuthType.NONE,
                        null,
                        true
                );

        System.out.println(
                "[TEST] MCP SERVER CONFIG CREATED"
        );

        System.out.println(
                "[TEST] TRANSPORT = "
                        + serverConfig.getTransport()
        );

        System.out.println(
                "[TEST] AUTH = "
                        + serverConfig.getAuthType()
        );

        System.out.println(
                "[TEST] ENABLED = "
                        + serverConfig.isEnabled()
        );


        // ========================================================
        // STEP 3 - CREATE REAL MCP CLIENT
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 2 -> CREATE REAL MCP CLIENT"
        );

        client =
                factory.create(serverConfig);

        assertNotNull(
                client,
                "MCP client must not be null"
        );

        System.out.println(
                "[TEST] REAL MCP CLIENT CREATED"
        );


        // ========================================================
        // STEP 4 - INITIALIZE
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 3 -> MCP INITIALIZE"
        );

        McpSchema.InitializeResult initializeResult =
                client.initialize();

        assertNotNull(
                initializeResult,
                "Initialize result must not be null"
        );

        System.out.println(
                "[TEST] INITIALIZE SUCCESS"
        );

        System.out.println(
                "[TEST] PROTOCOL VERSION = "
                        + initializeResult.protocolVersion()
        );

        System.out.println(
                "[TEST] SERVER INFO = "
                        + initializeResult.serverInfo()
        );

        assertNotNull(
                initializeResult.protocolVersion()
        );

        assertNotNull(
                initializeResult.serverInfo()
        );


        // ========================================================
        // STEP 5 - DISCOVER TOOLS
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 4 -> TOOLS/LIST"
        );

        McpSchema.ListToolsResult tools =
                client.listTools();

        assertNotNull(
                tools,
                "tools/list result must not be null"
        );

        assertNotNull(
                tools.tools(),
                "tools list must not be null"
        );

        System.out.println(
                "[TEST] TOOL COUNT = "
                        + tools.tools().size()
        );

        tools.tools().forEach(
                tool -> System.out.println(
                        "[TEST] DISCOVERED TOOL = "
                                + tool.name()
                )
        );


        assertEquals(
                1,
                tools.tools().size()
        );


        McpSchema.Tool echoTool =
                tools.tools()
                        .stream()
                        .filter(
                                tool ->
                                        "echo"
                                                .equals(tool.name())
                        )
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "echo tool not found"
                                        )
                        );


        assertEquals(
                "echo",
                echoTool.name()
        );

        assertEquals(
                "Echoes a message",
                echoTool.description()
        );

        assertNotNull(
                echoTool.inputSchema()
        );


        System.out.println(
                "[TEST] ECHO TOOL DISCOVERED"
        );

        System.out.println(
                "[TEST] DESCRIPTION = "
                        + echoTool.description()
        );


        // ========================================================
        // STEP 6 - CALL REAL MCP TOOL
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 5 -> TOOLS/CALL"
        );

        McpSchema.CallToolRequest request =
                McpSchema.CallToolRequest
                        .builder("echo")
                        .arguments(
                                Map.of(
                                        "message",
                                        "hello"
                                )
                        )
                        .build();


        System.out.println(
                "[TEST] TOOL NAME = echo"
        );

        System.out.println(
                "[TEST] ARGUMENT message = hello"
        );


        McpSchema.CallToolResult result =
                client.callTool(request);


        // ========================================================
        // STEP 7 - VERIFY TOOL RESULT
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 6 -> VERIFY TOOL RESULT"
        );


        assertNotNull(
                result,
                "Tool result must not be null"
        );

        assertFalse(
                Boolean.TRUE.equals(
                        result.isError()
                ),
                "Tool execution must not return error"
        );

        assertNotNull(
                result.content(),
                "Tool content must not be null"
        );

        assertEquals(
                1,
                result.content().size()
        );


        assertInstanceOf(
                McpSchema.TextContent.class,
                result.content().get(0)
        );


        McpSchema.TextContent textContent =
                (McpSchema.TextContent)
                        result.content().get(0);


        System.out.println(
                "[TEST] TOOL RESPONSE = "
                        + textContent.text()
        );


        assertEquals(
                "Echo: hello",
                textContent.text()
        );


        // ========================================================
        // STEP 8 - FINAL ASSERTIONS
        // ========================================================

        System.out.println();
        System.out.println(
                "[TEST] STEP 7 -> FINAL VERIFICATION"
        );

        assertNotNull(
                initializeResult
        );

        assertEquals(
                1,
                tools.tools().size()
        );

        assertEquals(
                "echo",
                echoTool.name()
        );

        assertEquals(
                "Echo: hello",
                textContent.text()
        );


        // ========================================================
        // SUCCESS
        // ========================================================

        System.out.println();
        System.out.println("=================================================");
        System.out.println(" TASK 4 PASSED");
        System.out.println("=================================================");
        System.out.println(
                " MCP client creation  -> PASS"
        );
        System.out.println(
                " Streamable HTTP      -> PASS"
        );
        System.out.println(
                " MCP initialize       -> PASS"
        );
        System.out.println(
                " Tool discovery       -> PASS"
        );
        System.out.println(
                " Tool execution       -> PASS"
        );
        System.out.println(
                " Tool response        -> PASS"
        );
        System.out.println("=================================================");
        System.out.println();
    }


    // ============================================================
    // TEST MCP SERVER CONFIGURATION
    // ============================================================

    @TestConfiguration
    static class McpTestServerConfiguration {


        // ========================================================
        // MCP JSON MAPPER
        // ========================================================

        @Bean
        McpJsonMapper mcpJsonMapper() {

            return McpJsonDefaults.getMapper();
        }


        // ========================================================
        // REAL STREAMABLE HTTP SERVER TRANSPORT
        // ========================================================

        @Bean
        HttpServletStreamableServerTransportProvider
        mcpTransportProvider(
                McpJsonMapper jsonMapper) {

            System.out.println(
                    "[MCP TEST SERVER] "
                            + "CREATING STREAMABLE HTTP TRANSPORT"
            );


            return
                    HttpServletStreamableServerTransportProvider
                            .builder()
                            .jsonMapper(jsonMapper)
                            .mcpEndpoint("/mcp")
                            .build();
        }


        // ========================================================
        // SERVLET REGISTRATION
        // ========================================================

        @Bean
        ServletRegistrationBean<Servlet>
        mcpServlet(
                HttpServletStreamableServerTransportProvider
                        transportProvider) {

            System.out.println(
                    "[MCP TEST SERVER] "
                            + "REGISTERING /mcp SERVLET"
            );


            ServletRegistrationBean<Servlet>
                    registration =
                    new ServletRegistrationBean<>(
                            transportProvider,
                            "/mcp"
                    );


            registration.setName(
                    "connectAiMcpTestServlet"
            );


            return registration;
        }


        // ========================================================
        // REAL MCP SERVER
        // ========================================================

        @Bean
        McpSyncServer mcpServer(
                HttpServletStreamableServerTransportProvider
                        transportProvider) {

            System.out.println(
                    "[MCP TEST SERVER] "
                            + "CREATING REAL MCP SERVER"
            );

            // ----------------------------------------------------
            // FIX: Tool.builder(name, schema) expects the schema as a
            // plain Map<String, Object>, not the typed McpSchema.JsonSchema
            // object - that typed constructor is used elsewhere in the SDK
            // (e.g. server-side schema validation helpers), not here.
            // Same JSON Schema content as before, just as a raw map.
            // ----------------------------------------------------
            Map<String, Object> echoSchema =
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "message", Map.of("type", "string")
                            ),
                            "required", List.of("message")
                    );


            McpSchema.Tool echoTool =
                    McpSchema.Tool
                            .builder(
                                    "echo",
                                    echoSchema
                            )
                            .description(
                                    "Echoes a message"
                            )
                            .build();


            SyncToolSpecification echoSpecification =
                    SyncToolSpecification
                            .builder()
                            .tool(echoTool)
                            .callHandler(
                                    (exchange, request) -> {

                                        String message =
                                                String.valueOf(
                                                        request
                                                                .arguments()
                                                                .get(
                                                                        "message"
                                                                )
                                                );


                                        System.out.println(
                                                "[MCP TEST SERVER] "
                                                        + "ECHO TOOL CALLED"
                                        );

                                        System.out.println(
                                                "[MCP TEST SERVER] "
                                                        + "MESSAGE = "
                                                        + message
                                        );


                                        return
                                                McpSchema.CallToolResult
                                                        .builder()
                                                        .content(
                                                                List.of(
                                                                        new McpSchema.TextContent(
                                                                                "Echo: "
                                                                                        + message
                                                                        )
                                                                )
                                                        )
                                                        .build();
                                    }
                            )
                            .build();


            McpSyncServer server =
                    McpServer
                            .sync(
                                    transportProvider
                            )
                            .serverInfo(
                                    "connect-ai-test-server",
                                    "1.0.0"
                            )
                            .capabilities(
                                    ServerCapabilities
                                            .builder()
                                            .tools(true)
                                            .build()
                            )
                            .tools(
                                    echoSpecification
                            )
                            .build();


            System.out.println(
                    "[MCP TEST SERVER] "
                            + "REAL MCP SERVER CREATED"
            );

            System.out.println(
                    "[MCP TEST SERVER] "
                            + "TOOL REGISTERED = echo"
            );


            return server;
        }


        // ========================================================
        // CLIENT FACTORY
        // ========================================================

        @Bean
        SecretEncryptionService
        secretEncryptionService() {

            return new SecretEncryptionService(
                    "integration-test-key"
            );
        }


        @Bean
        McpClientFactory mcpClientFactory(
                SecretEncryptionService
                        secretEncryptionService) {

            return new McpClientFactory(
                    secretEncryptionService,
                    10,
                    5
            );
        }
    }
}