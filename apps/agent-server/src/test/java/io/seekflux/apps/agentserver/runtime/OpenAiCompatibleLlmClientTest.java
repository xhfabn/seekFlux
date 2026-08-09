package io.seekflux.apps.agentserver.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentDecisionContext;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentToolObservation;
import io.seekflux.platform.agentruntime.AgentToolResult;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import io.seekflux.platform.agentruntime.context.ContextMessage;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleLlmClientTest {

    @Test
    void callsChatCompletionsAndParsesStructuredDecision() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String decision = "{\"action\":\"call_tool\",\"tool\":\"search_direct\","
                    + "\"arguments\":{\"query\":\"杭州露营\"}}";
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", decision)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    HttpClient.newHttpClient(),
                    objectMapper,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions"),
                    "test-key",
                    "test-model",
                    Duration.ofSeconds(1));
            AgentRunRequest run = new AgentRunRequest(
                    "request-1", "session-1", "turn-1", "杭州露营", Map.of());
            AgentDecisionContext decisionContext = new AgentDecisionContext(
                    run, 1, Duration.ofSeconds(1), List.of());

            AgentDecision decision = client.chat(new AssembledContext(
                    decisionContext,
                    List.of(
                            new ContextMessage("system", "structured prompt"),
                            new ContextMessage("user", "杭州露营")),
                    "prompt-spec-1",
                    12));

            assertThat(decision).isEqualTo(new AgentDecision.CallTool(
                    "search_direct", Map.of("query", "杭州露营")));
            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(requestBody.get()).contains("test-model", "structured prompt", "json_object");
            assertThat(client.version()).isEqualTo("openai-compatible:test-model:v1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesASelectedToolReferenceToTheActualCandidateSet() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8));
            String decision = "{\"action\":\"complete\",\"output\":{\"selectedTool\":\"search_filtered\"}}";
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", decision)))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    HttpClient.newHttpClient(), objectMapper,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions"),
                    "", "test-model", Duration.ofSeconds(1));
            AgentRunRequest run = new AgentRunRequest(
                    "request-2", "session-2", "turn-2", "精确结果", Map.of());
            AgentToolObservation observation = new AgentToolObservation(
                    "call-1", "search_filtered", "v1", Map.of(), false,
                    AgentToolResult.success(Map.of("marker", "actual-candidates"), "trace-1"), 1);
            AgentDecisionContext decisionContext = new AgentDecisionContext(
                    run, 2, Duration.ofSeconds(1), List.of(observation));

            AgentDecision decision = client.chat(new AssembledContext(
                    decisionContext,
                    List.of(new ContextMessage("tool", "search_filtered:{total=1}")),
                    "prompt-spec-2",
                    12));

            assertThat(decision).isInstanceOfSatisfying(AgentDecision.Complete.class, complete -> {
                assertThat(complete.output()).containsEntry("marker", "actual-candidates");
                assertThat(complete.output()).containsEntry("selectedTool", "search_filtered");
                assertThat(complete.output()).containsEntry("successfulToolCount", 1L);
                assertThat(complete.output()).containsEntry("candidateSetReused", true);
            });
            assertThat(requestBody.get()).contains("[tool_observation]", "\"role\":\"user\"");
        } finally {
            server.stop(0);
        }
    }
}
