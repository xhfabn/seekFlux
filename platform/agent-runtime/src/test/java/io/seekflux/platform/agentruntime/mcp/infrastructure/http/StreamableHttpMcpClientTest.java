package io.seekflux.platform.agentruntime.mcp.infrastructure.http;

import io.seekflux.platform.agentruntime.mcp.connection.McpConnectionManager;
import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamableHttpMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CountDownLatch slowCallStarted = new CountDownLatch(1);
    private final CountDownLatch cancellationReceived = new CountDownLatch(1);
    private HttpServer server;
    private ExecutorService serverExecutor;
    private URI endpoint;
    private boolean requireCredentials = true;
    private boolean wrongResponseId;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/mcp", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void negotiatesSessionDiscoversAndCallsOverRealStreamableHttp() {
        try (StreamableHttpMcpClient client = client(4096)) {
            var tools = client.initializeAndList(new CancellationToken());
            McpCallResult result = client.callTool(
                    "echo", Map.of("text", "hello"), Duration.ofSeconds(2),
                    new CancellationToken());

            assertThat(tools).extracting(McpRemoteTool::name).containsExactly("echo", "slow", "large");
            assertThat(result.error()).isFalse();
            assertThat(result.result()).containsKey("structuredContent");
        }
    }

    @Test
    void plainJavaConsumerUsesDefaultManagerWithOnlyServerAndToolConfiguration() {
        requireCredentials = false;
        McpToolPolicy policy = new McpToolPolicy("echo", "v1",
                io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool.Effect.READ_ONLY,
                false, Set.of(), "");
        var registry = new io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry(
                java.util.List.of(),
                io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy.SAFE_ONLY);
        try (McpConnectionManager manager = new McpConnectionManager(
                java.util.List.of(McpServerConfig.streamableHttp("docs", endpoint, Map.of("echo", policy))),
                registry)) {
            manager.start();
            var context = new io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext(
                    "run", "call", new io.seekflux.platform.agentruntime.application.command.AgentRunRequest(
                            "request", "session", "turn", "hello", Map.of()),
                    Map.of("text", "hello"), Duration.ofSeconds(2));
            assertThat(registry.require("docs__echo").execute(context).success()).isTrue();
            assertThat(manager.health("docs").activeToolCount()).isEqualTo(1);
        }
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void rejectsResponseBelongingToAnotherRequest() {
        try (StreamableHttpMcpClient client = client(4096)) {
            client.initializeAndList(new CancellationToken());
            wrongResponseId = true;
            assertThatThrownBy(() -> client.callTool("echo", Map.of(), Duration.ofSeconds(2),
                    new CancellationToken())).hasMessage("MCP_RESPONSE_INVALID");
        }
    }

    @Test
    void propagatesLocalCancellationAndNotifiesRemotePeer() throws Exception {
        try (StreamableHttpMcpClient client = client(4096)) {
            client.initializeAndList(new CancellationToken());
            CancellationToken token = new CancellationToken();
            CompletableFuture<McpCallResult> call = CompletableFuture.supplyAsync(() ->
                    client.callTool("slow", Map.of(), Duration.ofSeconds(5), token));
            assertThat(slowCallStarted.await(2, TimeUnit.SECONDS)).isTrue();

            token.cancel(CancellationCause.USER_CANCEL);

            assertThatThrownBy(call::join)
                    .hasRootCauseInstanceOf(AgentCancellationException.class);
            assertThat(cancellationReceived.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void mapsTransportTimeoutAndNotifiesRemotePeer() throws Exception {
        try (StreamableHttpMcpClient client = client(4096)) {
            client.initializeAndList(new CancellationToken());

            assertThatThrownBy(() -> client.callTool(
                    "slow", Map.of(), Duration.ofMillis(60), new CancellationToken()))
                    .isInstanceOf(McpException.class)
                    .extracting(error -> ((McpException) error).code())
                    .isEqualTo("MCP_REQUEST_TIMEOUT");
            assertThat(cancellationReceived.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void abortsAnOversizedBodyBeforePublishingIt() {
        try (StreamableHttpMcpClient client = client(1024)) {
            client.initializeAndList(new CancellationToken());

            assertThatThrownBy(() -> client.callTool(
                    "large", Map.of(), Duration.ofSeconds(2), new CancellationToken()))
                    .isInstanceOf(McpException.class)
                    .extracting(error -> ((McpException) error).code())
                    .isEqualTo("MCP_RESPONSE_TOO_LARGE");
        }
    }

    private StreamableHttpMcpClient client(int maxResponseBytes) {
        McpToolPolicy policy = new McpToolPolicy(
                "echo", "policy-v1",
                io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool.Effect.READ_ONLY,
                false, Set.of(), "");
        McpServerConfig config = new McpServerConfig(
                1, "fake", "config-v1", McpServerConfig.Transport.STREAMABLE_HTTP,
                endpoint, "env:TEST_TOKEN", McpServerConfig.SUPPORTED_PROTOCOL_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(5), 2, 3,
                Duration.ofSeconds(2), maxResponseBytes, Map.of("echo", policy));
        return new StreamableHttpMcpClient(
                config, objectMapper,
                ref -> Map.of("Authorization", "Bearer integration-secret"));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (requireCredentials) {
                assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer integration-secret");
            }
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            JsonNode request = requestBody.length == 0
                    ? objectMapper.createObjectNode() : objectMapper.readTree(requestBody);
            String method = request.path("method").asText();
            if ("notifications/initialized".equals(method)) {
                assertProtocolAndSession(exchange);
                respond(exchange, 202, "", null);
                return;
            }
            if ("notifications/cancelled".equals(method)) {
                assertProtocolAndSession(exchange);
                cancellationReceived.countDown();
                respond(exchange, 202, "", null);
                return;
            }
            long id = request.path("id").asLong();
            if ("initialize".equals(method)) {
                respond(exchange, 200, rpc(id, Map.of(
                        "protocolVersion", McpServerConfig.SUPPORTED_PROTOCOL_VERSION,
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "controlled-fake", "version", "1"))),
                        "session-1");
                return;
            }
            assertProtocolAndSession(exchange);
            if ("tools/list".equals(method)) {
                respondSse(exchange, rpc(id, Map.of("tools", java.util.List.of(
                        tool("echo"), tool("slow"), tool("large")))));
                return;
            }
            if ("tools/call".equals(method)) {
                String name = request.path("params").path("name").asText();
                if ("slow".equals(name)) {
                    slowCallStarted.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                Map<String, Object> result = "large".equals(name)
                        ? Map.of("content", java.util.List.of(Map.of(
                                "type", "text", "text", "x".repeat(2_000))))
                        : Map.of(
                                "content", java.util.List.of(Map.of("type", "text", "text", "ok")),
                                "structuredContent", Map.of("echoed", true),
                                "isError", false);
                respond(exchange, 200, rpc(wrongResponseId ? id + 1 : id, result), null);
                return;
            }
            respond(exchange, 404, "", null);
        }
    }

    private void assertProtocolAndSession(HttpExchange exchange) {
        assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                .isEqualTo(McpServerConfig.SUPPORTED_PROTOCOL_VERSION);
        assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
                .isEqualTo("session-1");
    }

    private Map<String, Object> tool(String name) {
        return Map.of(
                "name", name,
                "description", name,
                "inputSchema", Map.of("type", "object", "properties", Map.of()));
    }

    private String rpc(long id, Map<String, Object> result) throws IOException {
        return objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }

    private static void respond(
            HttpExchange exchange, int status, String body, String session) throws IOException {
        if (session != null) {
            exchange.getResponseHeaders().add("Mcp-Session-Id", session);
        }
        if (!body.isEmpty()) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void respondSse(HttpExchange exchange, String json) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        byte[] bytes = ("event: message\ndata: " + json + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
