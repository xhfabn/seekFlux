package io.seekflux.platform.agentruntime.mcp.infrastructure.http;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal MCP 2025-11-25 client for the Streamable HTTP Tool subset. */
public final class StreamableHttpMcpClient implements McpClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int MAX_TOOL_PAGES = 32;
    private static final int MAX_DISCOVERED_TOOLS = 256;

    private final McpServerConfig config;
    private final ObjectMapper objectMapper;
    private final McpCredentialProvider credentials;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile String sessionId;
    private volatile boolean initialized;

    public StreamableHttpMcpClient(
            McpServerConfig config,
            ObjectMapper objectMapper,
            McpCredentialProvider credentials) {
        this.config = java.util.Objects.requireNonNull(config, "MCP config must not be null");
        this.objectMapper = java.util.Objects.requireNonNull(
                objectMapper, "ObjectMapper must not be null");
        this.credentials = credentials == null ? new EnvironmentMcpCredentialProvider() : credentials;
        if (config.transport() != McpServerConfig.Transport.STREAMABLE_HTTP
                || !McpServerConfig.SUPPORTED_PROTOCOL_VERSION.equals(config.protocolVersion())) {
            throw new McpException("MCP_DEFAULT_CLIENT_CONFIG_UNSUPPORTED", false);
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .build();
    }

    @Override
    public synchronized List<McpRemoteTool> initializeAndList(
            CancellationToken cancellationToken) {
        if (!initialized) {
            long id = requestIds.incrementAndGet();
            RpcResponse initializedResponse = request(
                    id,
                    "initialize",
                    Map.of(
                            "protocolVersion", config.protocolVersion(),
                            "capabilities", Map.of(),
                            "clientInfo", Map.of(
                                    "name", "seekflux-agent-runtime",
                                    "version", "1.0")),
                    config.requestTimeout(),
                    cancellationToken);
            String negotiated = initializedResponse.result()
                    .path("protocolVersion").asText("");
            if (!config.protocolVersion().equals(negotiated)) {
                throw new McpException("MCP_PROTOCOL_VERSION_MISMATCH", true);
            }
            if (!initializedResponse.result().path("capabilities").has("tools")) {
                throw new McpException("MCP_TOOLS_CAPABILITY_MISSING", false);
            }
            initializedResponse.headers().firstValue("Mcp-Session-Id")
                    .ifPresent(value -> sessionId = value);
            initialized = true;
            try {
                notification("notifications/initialized", Map.of(), cancellationToken);
            } catch (RuntimeException failed) {
                initialized = false;
                throw failed;
            }
        }
        List<McpRemoteTool> discovered = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            Map<String, Object> parameters = cursor == null
                    ? Map.of() : Map.of("cursor", cursor);
            RpcResponse response = request(
                    requestIds.incrementAndGet(), "tools/list", parameters,
                    config.requestTimeout(), cancellationToken);
            JsonNode tools = response.result().path("tools");
            if (!tools.isArray()) {
                throw new McpException("MCP_TOOLS_LIST_INVALID", false);
            }
            for (JsonNode tool : tools) {
                Map<String, Object> schema = objectMapper.convertValue(
                        tool.path("inputSchema"), MAP_TYPE);
                discovered.add(new McpRemoteTool(
                        tool.path("name").asText(""),
                        tool.path("description").asText(""),
                        schema));
                if (discovered.size() > MAX_DISCOVERED_TOOLS) {
                    throw new McpException("MCP_TOOL_COUNT_EXCEEDED", false);
                }
            }
            cursor = response.result().path("nextCursor").isTextual()
                    ? response.result().path("nextCursor").asText() : null;
            if (cursor == null || cursor.isBlank()) {
                return List.copyOf(discovered);
            }
        }
        throw new McpException("MCP_TOOL_PAGINATION_EXCEEDED", false);
    }

    @Override
    public McpCallResult callTool(
            String remoteToolName,
            Map<String, Object> arguments,
            Duration timeout,
            CancellationToken cancellationToken) {
        if (!initialized) {
            throw new McpException("MCP_CLIENT_NOT_INITIALIZED", true);
        }
        RpcResponse response = request(
                requestIds.incrementAndGet(),
                "tools/call",
                Map.of(
                        "name", remoteToolName,
                        "arguments", arguments == null ? Map.of() : arguments),
                smaller(timeout, config.requestTimeout()),
                cancellationToken);
        Map<String, Object> result = objectMapper.convertValue(response.result(), MAP_TYPE);
        return new McpCallResult(Boolean.TRUE.equals(result.get("isError")), result);
    }

    @Override
    public synchronized void close() {
        String currentSession = sessionId;
        initialized = false;
        sessionId = null;
        if (currentSession == null || currentSession.isBlank()) {
            return;
        }
        try {
            HttpRequest.Builder request = baseRequest(config.endpoint())
                    .DELETE()
                    .timeout(Duration.ofSeconds(1))
                    .header("Mcp-Session-Id", currentSession)
                    .header("MCP-Protocol-Version", config.protocolVersion());
            httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) {
            // Closing is best effort and must not expose credentials or alter Runtime state.
        }
    }

    private RpcResponse request(
            long id,
            String method,
            Map<String, Object> params,
            Duration timeout,
            CancellationToken cancellationToken) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        if (params != null && !params.isEmpty()) {
            payload.put("params", params);
        }
        HttpResponse<byte[]> response = send(payload, id, timeout, cancellationToken);
        JsonNode envelope = responseEnvelope(response, id);
        if (envelope == null || !envelope.isObject()
                || !"2.0".equals(envelope.path("jsonrpc").asText())
                || !envelope.path("id").isIntegralNumber()
                || envelope.path("id").asLong() != id) {
            throw new McpException("MCP_RESPONSE_INVALID", false);
        }
        if (envelope.has("error")) {
            throw new McpException("MCP_PROTOCOL_ERROR", false);
        }
        JsonNode result = envelope.get("result");
        if (result == null || !result.isObject()) {
            throw new McpException("MCP_RESPONSE_INVALID", false);
        }
        return new RpcResponse(result, response.headers());
    }

    private void notification(String method, Map<String, Object> params, CancellationToken token) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        if (params != null && !params.isEmpty()) {
            payload.put("params", params);
        }
        send(payload, null, config.requestTimeout(), token);
    }

    private HttpResponse<byte[]> send(
            Map<String, Object> payload,
            Long requestId,
            Duration timeout,
            CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null
                ? new CancellationToken() : cancellationToken;
        token.throwIfCancelled();
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (Exception invalid) {
            throw new McpException("MCP_REQUEST_SERIALIZATION_FAILED", false, invalid);
        }
        HttpRequest.Builder builder = baseRequest(config.endpoint())
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (initialized) {
            builder.header("MCP-Protocol-Version", config.protocolVersion());
        }
        String currentSession = sessionId;
        if (currentSession != null && !currentSession.isBlank()) {
            builder.header("Mcp-Session-Id", currentSession);
        }
        CompletableFuture<HttpResponse<byte[]>> future = httpClient.sendAsync(
                builder.build(), limitedBody(config.maxResponseBytes()));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                token.throwIfCancelled();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    cancelRemote(requestId, "request timeout");
                    throw new McpException("MCP_REQUEST_TIMEOUT", true);
                }
                try {
                    HttpResponse<byte[]> response = future.get(
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                            TimeUnit.NANOSECONDS);
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new McpException(
                                response.statusCode() == 404 && currentSession != null
                                        ? "MCP_SESSION_EXPIRED" : response.statusCode() >= 500
                                        ? "MCP_SERVER_UNAVAILABLE" : "MCP_HTTP_REJECTED",
                                response.statusCode() >= 500
                                        || response.statusCode() == 404 && currentSession != null);
                    }
                    return response;
                } catch (TimeoutException polling) {
                    // Poll the Runtime cancellation token while the HTTP exchange is in flight.
                }
            }
        } catch (AgentCancellationException cancelled) {
            future.cancel(true);
            cancelRemote(requestId, cancelled.cancellationCause().name());
            throw cancelled;
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            cancelRemote(requestId, "thread interrupted");
            throw new McpException("MCP_REQUEST_INTERRUPTED", true, interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed;
            while (cause.getCause() != null && !(cause instanceof McpException)) {
                cause = cause.getCause();
            }
            if (cause instanceof McpException mcp) {
                throw mcp;
            }
            if (cause instanceof java.net.http.HttpTimeoutException) {
                cancelRemote(requestId, "request timeout");
                throw new McpException("MCP_REQUEST_TIMEOUT", true, cause);
            }
            throw new McpException("MCP_CONNECTION_FAILED", true, cause);
        }
    }

    private JsonNode responseEnvelope(HttpResponse<byte[]> response, long requestId) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                return objectMapper.readTree(body);
            }
            JsonNode matched = null;
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\R", -1)) {
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                } else if (line.isBlank() && !data.isEmpty()) {
                    JsonNode event = objectMapper.readTree(data.toString());
                    if (event != null && event.path("id").asLong(Long.MIN_VALUE) == requestId) {
                        matched = event;
                    }
                    data.setLength(0);
                }
            }
            if (!data.isEmpty()) {
                JsonNode event = objectMapper.readTree(data.toString());
                if (event != null && event.path("id").asLong(Long.MIN_VALUE) == requestId) {
                    matched = event;
                }
            }
            if (matched == null) {
                throw new McpException("MCP_SSE_RESPONSE_MISSING", true);
            }
            return matched;
        } catch (McpException error) {
            throw error;
        } catch (Exception invalid) {
            throw new McpException("MCP_RESPONSE_INVALID", false, invalid);
        }
    }

    private HttpRequest.Builder baseRequest(URI endpoint) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);
        Map<String, String> headers = Optional.ofNullable(
                credentials.headers(config.credentialRef())).orElse(Map.of());
        headers.forEach((name, value) -> {
            if (name == null || value == null || name.contains("\r") || name.contains("\n")
                    || value.contains("\r") || value.contains("\n")
                    || "host".equalsIgnoreCase(name)
                    || "content-length".equalsIgnoreCase(name)
                    || "content-type".equalsIgnoreCase(name)
                    || "accept".equalsIgnoreCase(name)
                    || "mcp-protocol-version".equalsIgnoreCase(name)
                    || "mcp-session-id".equalsIgnoreCase(name)) {
                throw new McpException("MCP_CREDENTIAL_HEADER_INVALID", false);
            }
            builder.header(name, value);
        });
        return builder;
    }

    private void cancelRemote(Long requestId, String reason) {
        if (requestId == null) {
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/cancelled",
                    "params", Map.of("requestId", requestId, "reason", reason));
            byte[] body = objectMapper.writeValueAsBytes(payload);
            HttpRequest.Builder builder = baseRequest(config.endpoint())
                    .timeout(Duration.ofSeconds(1))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("MCP-Protocol-Version", config.protocolVersion())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (sessionId != null && !sessionId.isBlank()) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // The local cancellation remains authoritative even if the peer cannot be notified.
        }
    }

    private static HttpResponse.BodyHandler<byte[]> limitedBody(int maxBytes) {
        return ignored -> new LimitedBodySubscriber(maxBytes);
    }

    private static Duration smaller(Duration left, Duration right) {
        if (left == null || left.isZero() || left.isNegative()) {
            return right;
        }
        return left.compareTo(right) < 0 ? left : right;
    }

    private record RpcResponse(JsonNode result, java.net.http.HttpHeaders headers) {
    }

    private static final class LimitedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    if (output.size() + buffer.remaining() > maxBytes) {
                        subscription.cancel();
                        body.completeExceptionally(new McpException(
                                "MCP_RESPONSE_TOO_LARGE", false));
                        return;
                    }
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    output.writeBytes(bytes);
                }
                subscription.request(1);
            } catch (RuntimeException failed) {
                subscription.cancel();
                body.completeExceptionally(failed);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
