package io.seekflux.agent.infrastructure.llm.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.exception.AgentModelOutputException;
import io.seekflux.platform.agentruntime.domain.exception.ContextOverflowException;
import io.seekflux.platform.agentruntime.application.spi.business.output.OutputGuardPolicy;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
                    "choices", List.of(Map.of("message", Map.of("content", decision))),
                    "usage", Map.of(
                            "prompt_tokens", 100,
                            "completion_tokens", 25,
                            "total_tokens", 125)));
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
                    Duration.ofSeconds(1),
                    2.0,
                    8.0);
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

            var measured = client.chatWithUsage(new AssembledContext(
                    decisionContext,
                    List.of(new ContextMessage("system", "structured prompt")),
                    "prompt-spec-1",
                    12));
            assertThat(measured.usage().totalTokens()).isEqualTo(125);
            assertThat(measured.usage().costMicros()).isEqualTo(400);
            assertThat(measured.usage().measured()).isTrue();
            assertThat(measured.assistantContent().content()).contains("call_tool");
            assertThat(measured.assistantContent().reasoning()).isNull();
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

    @Test
    void acceptsStructuredDecisionFromReasoningContentWhenContentIsAbsent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String decision = "{\"action\":\"clarify\",\"question\":\"请补充主题\"}";
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of(
                            "role", "assistant",
                            "reasoning_content", decision))),
                    "usage", Map.of("prompt_tokens", 20, "completion_tokens", 12, "total_tokens", 32)));
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
                    "", "longcat-compatible-model", Duration.ofSeconds(1));
            AgentRunRequest run = new AgentRunRequest(
                    "request-3", "session-3", "turn-3", "帮我找内容", Map.of());
            AgentDecisionContext decisionContext = new AgentDecisionContext(
                    run, 1, Duration.ofSeconds(1), List.of());

            var result = client.chatWithUsage(new AssembledContext(
                    decisionContext,
                    List.of(new ContextMessage("user", "帮我找内容")),
                    "prompt-spec-3",
                    12));

            assertThat(result.decision()).isEqualTo(new AgentDecision.Clarify("请补充主题"));
            assertThat(result.usage().totalTokens()).isEqualTo(32);
            assertThat(result.usage().measured()).isTrue();
            assertThat(result.assistantContent().content()).contains("clarify");
            assertThat(result.assistantContent().reasoning()).contains("请补充主题");
            assertThat(result.assistantContent().reasoningReplayable()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptedProviderCallPreservesTheCancellationCause() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CountDownLatch requestEntered = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestEntered.countDown();
            try {
                releaseResponse.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                    HttpClient.newHttpClient(), objectMapper,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/v1/chat/completions"),
                    "", "test-model", Duration.ofSeconds(10));
            AgentRunRequest run = new AgentRunRequest(
                    "request-4", "session-4", "turn-4", "取消", Map.of());
            AgentDecisionContext decisionContext = new AgentDecisionContext(
                    run, 1, Duration.ofSeconds(10), List.of());
            AssembledContext context = new AssembledContext(
                    decisionContext,
                    List.of(new ContextMessage("user", "取消")),
                    "prompt-spec-4",
                    2);
            CancellationToken token = new CancellationToken();
            AtomicReference<Thread> caller = new AtomicReference<>();

            var result = executor.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    client.chatWithUsage(context, token);
                    return null;
                } catch (AgentCancellationException cancelled) {
                    return cancelled.cancellationCause();
                }
            });
            assertThat(requestEntered.await(1, TimeUnit.SECONDS)).isTrue();

            token.cancel(CancellationCause.USER_CANCEL);
            caller.get().interrupt();

            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(CancellationCause.USER_CANCEL);
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void repairsOneInvalidStructuredOutputAndRecordsTheGuardLifecycle() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> repairBody = new AtomicReference<>();
        List<ContextEvent> events = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int call = calls.incrementAndGet();
            if (call == 2) {
                repairBody.set(body);
            }
            String content = call == 1
                    ? "not-json"
                    : "{\"action\":\"clarify\",\"question\":\"请补充地点\"}";
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server, objectMapper, OutputGuardPolicy.REPAIR_THEN_DEGRADE, events);

            var result = client.chatWithUsage(context("request-repair"));

            assertThat(result.decision()).isEqualTo(new AgentDecision.Clarify("请补充地点"));
            assertThat(result.usage().totalTokens()).isEqualTo(30);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(repairBody.get()).contains("output_repair", "not-json");
            assertThat(events).extracting(ContextEvent::type).containsExactly(
                    ContextEvent.Type.OUTPUT_REPAIR,
                    ContextEvent.Type.OUTPUT_ACCEPTED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void degradesAfterTheConfiguredOutputRepairAttemptsAreExhausted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ContextEvent> events = new ArrayList<>();
        HttpServer server = invalidOutputServer(objectMapper);
        server.start();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server,
                    objectMapper,
                    new OutputGuardPolicy(1, OutputGuardPolicy.ExhaustedAction.DEGRADE),
                    events);

            var result = client.chatWithUsage(context("request-degrade"));

            assertThat(result.decision()).isEqualTo(
                    new AgentDecision.Fallback("LLM_OUTPUT_INVALID"));
            assertThat(result.assistantContent()).isEqualTo(
                    io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent.EMPTY);
            assertThat(events).extracting(ContextEvent::type).contains(
                    ContextEvent.Type.OUTPUT_REPAIR,
                    ContextEvent.Type.OUTPUT_DEGRADED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interruptedRepairCallPreservesTheCancellationCause() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch repairEntered = new CountDownLatch(1);
        CountDownLatch releaseRepair = new CountDownLatch(1);
        List<ContextEvent> events = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            if (calls.incrementAndGet() == 1) {
                byte[] response = objectMapper.writeValueAsBytes(Map.of(
                        "choices", List.of(Map.of("message", Map.of("content", "invalid")))));
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            repairEntered.countDown();
            try {
                releaseRepair.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server, objectMapper, OutputGuardPolicy.REPAIR_THEN_DEGRADE, events);
            CancellationToken token = new CancellationToken();
            AtomicReference<Thread> caller = new AtomicReference<>();

            var result = executor.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    client.chatWithUsage(context("request-repair-cancel"), token);
                    return null;
                } catch (AgentCancellationException cancelled) {
                    return cancelled.cancellationCause();
                }
            });
            assertThat(repairEntered.await(1, TimeUnit.SECONDS)).isTrue();

            token.cancel(CancellationCause.USER_CANCEL);
            caller.get().interrupt();

            assertThat(result.get(1, TimeUnit.SECONDS)).isEqualTo(CancellationCause.USER_CANCEL);
            assertThat(events).extracting(ContextEvent::type)
                    .containsExactly(ContextEvent.Type.OUTPUT_REPAIR);
        } finally {
            releaseRepair.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void returnsAStableFailureWhenOutputGuardIsConfiguredToFailClosed() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<ContextEvent> events = new ArrayList<>();
        HttpServer server = invalidOutputServer(objectMapper);
        server.start();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server,
                    objectMapper,
                    new OutputGuardPolicy(0, OutputGuardPolicy.ExhaustedAction.FAIL),
                    events);

            assertThatThrownBy(() -> client.chatWithUsage(context("request-fail")))
                    .isInstanceOfSatisfying(AgentModelOutputException.class, error ->
                            assertThat(error.code()).isEqualTo("LLM_OUTPUT_GUARD_EXHAUSTED"));
            assertThat(events).extracting(ContextEvent::type)
                    .containsExactly(ContextEvent.Type.OUTPUT_EXHAUSTED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesOnlyPreOutput400Or413ContextOverflowForTheLoopRetry() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"context_length_exceeded\"}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server, objectMapper, OutputGuardPolicy.REPAIR_THEN_DEGRADE, new ArrayList<>());

            assertThatThrownBy(() -> client.chatWithUsage(context("request-overflow")))
                    .isInstanceOfSatisfying(ContextOverflowException.class, error ->
                            assertThat(error.statusCode()).isEqualTo(400));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesAnyProvider413AsContextOverflow() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "payload too large".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(413, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleLlmClient client = guardedClient(
                    server, objectMapper, OutputGuardPolicy.REPAIR_THEN_DEGRADE, new ArrayList<>());

            assertThatThrownBy(() -> client.chatWithUsage(context("request-overflow-413")))
                    .isInstanceOfSatisfying(ContextOverflowException.class, error ->
                            assertThat(error.statusCode()).isEqualTo(413));
        } finally {
            server.stop(0);
        }
    }

    private static OpenAiCompatibleLlmClient guardedClient(
            HttpServer server,
            ObjectMapper objectMapper,
            OutputGuardPolicy policy,
            List<ContextEvent> events) {
        return new OpenAiCompatibleLlmClient(
                HttpClient.newHttpClient(),
                objectMapper,
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/v1/chat/completions"),
                "",
                "test-model",
                Duration.ofSeconds(1),
                0,
                0,
                policy,
                events::add,
                Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC));
    }

    private static AssembledContext context(String requestId) {
        AgentRunRequest run = new AgentRunRequest(
                requestId, "session-guard", "turn-guard", "帮我找内容", Map.of());
        return new AssembledContext(
                new AgentDecisionContext(run, 1, Duration.ofSeconds(1), List.of()),
                List.of(new ContextMessage("user", "帮我找内容")),
                "guard-spec",
                12);
    }

    private static HttpServer invalidOutputServer(ObjectMapper objectMapper) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = objectMapper.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", "still-invalid")))));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        return server;
    }
}
