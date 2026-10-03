package io.seekflux.platform.agentruntime.mcp.connection;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.schema.McpSchemaTranslator;
import io.seekflux.platform.agentruntime.mcp.infrastructure.tool.McpProxyTool;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpEvent;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.spi.McpEventRecorder;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolSchemaAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpConnectionManagerTest {

    @Test
    void cancellationDuringReconnectUsesTheOriginalTokenAndNeverCallsTheTool() {
        CancellationToken token = new CancellationToken();
        FakeClient client = new FakeClient(List.of(schema("lookup", "string"))) {
            @Override
            public List<McpRemoteTool> initializeAndList(CancellationToken received) {
                assertThat(received).isSameAs(token);
                token.cancel(io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause.USER_CANCEL);
                received.throwIfCancelled();
                return List.of();
            }
        };
        AgentToolRegistry registry = registry(List.of());
        try (McpConnectionManager manager = manager(config(readPolicy()), registry,
                new QueueFactory(client), McpEventRecorder.NOOP)) {
            assertThatThrownBy(() -> manager.call("docs", "docs__lookup", "v1", "lookup",
                    Map.of(), Duration.ofSeconds(1), token))
                    .isInstanceOf(io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException.class);
            assertThat(client.calls).hasValue(0);
            assertThat(registry.names()).isEmpty();
        }
    }

    @Test
    void expiredDeadlineDoesNotConnectOrExecute() {
        try (McpConnectionManager manager = manager(config(readPolicy()), registry(List.of()),
                server -> { throw new AssertionError("deadline must stop discovery"); }, McpEventRecorder.NOOP)) {
            assertThatThrownBy(() -> manager.call("docs", "docs__lookup", "v1", "lookup",
                    Map.of(), Duration.ZERO, new CancellationToken())).hasMessage("MCP_REQUEST_TIMEOUT");
        }
    }

    @Test
    void customBusinessReceiptAndStatusMappingDoesNotReplayMutation() {
        McpToolPolicy policy = new McpToolPolicy("publish", "v1", AgentTool.Effect.MUTATING,
                true, Set.of("tenant-a"), "status");
        McpRemoteTool statusTool = new McpRemoteTool("status", "", Map.of("type", "object",
                "properties", Map.of("operationId", Map.of("type", "string")),
                "required", List.of("operationId")));
        AtomicInteger writes = new AtomicInteger();
        FakeClient client = new FakeClient(List.of(schema("publish", "string"), statusTool)) {
            @Override
            public McpCallResult callTool(String tool, Map<String, Object> arguments,
                    Duration timeout, CancellationToken token) {
                if ("publish".equals(tool)) {
                    writes.incrementAndGet();
                } else {
                    assertThat(arguments).containsOnlyKeys("operationId").containsEntry("operationId", "idem-1");
                }
                return new McpCallResult(false, Map.of("structuredContent", Map.of("state", "DONE")));
            }
        };
        McpToolResultAdapter results = new McpToolResultAdapter() {
            @Override
            public AgentToolResult convert(McpServerConfig server, McpToolPolicy tool, McpCallResult result) {
                return AgentToolResult.success(Map.of("completed", true), null, Map.of("receipt", "custom"));
            }

            @Override
            public Map<String, Object> reconciliationArguments(McpServerConfig server,
                    McpToolPolicy tool, String idempotencyKey, String toolCallId) {
                return Map.of("operationId", idempotencyKey);
            }

            @Override
            public SideEffectReconciliation reconcile(McpServerConfig server,
                    McpToolPolicy tool, McpCallResult result) {
                return SideEffectReconciliation.succeeded(convert(server, tool, result), "BUSINESS_STATUS");
            }
        };
        AgentToolRegistry registry = registry(List.of());
        try (McpConnectionManager manager = new McpConnectionManager(List.of(config(policy)), registry,
                new QueueFactory(client), new McpSchemaTranslator(), McpEventRecorder.NOOP,
                Clock.systemUTC(), McpToolAuthorizer.ALLOW, results)) {
            manager.start();
            McpProxyTool proxy = (McpProxyTool) registry.require("docs__publish");
            var now = java.time.Instant.now();
            var entry = new io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry(
                    1, "ledger", "session", "request", "turn", "attempt", 1, 0, "call-1", proxy.name(),
                    proxy.schema().version(), "idem-1",
                    io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectStatus.UNKNOWN,
                    "digest", null, null, null, null, now, now);
            assertThat(proxy.reconcile(entry, context("tenant-a", Map.of())).resolution())
                    .isEqualTo(SideEffectReconciliation.Resolution.SUCCEEDED);
            assertThat(writes).hasValue(0);
        }
    }

    @Test
    void hostAuthorizationCanRestrictButCannotBypassLocalAllowlists() {
        FakeClient client = fake(schema("lookup", "string"));
        AgentToolRegistry registry = registry(List.of());
        AtomicInteger checks = new AtomicInteger();
        try (McpConnectionManager manager = new McpConnectionManager(
                List.of(config(readPolicy())), registry, new QueueFactory(client),
                new McpSchemaTranslator(), McpEventRecorder.NOOP, Clock.systemUTC(),
                (server, policy, context) -> { checks.incrementAndGet(); return false; },
                McpToolResultAdapter.DEFAULT)) {
            manager.start();
            AgentTool proxy = registry.require("docs__lookup");
            assertThat(proxy.execute(context("tenant-b", Map.of("query", "x"))).success()).isFalse();
            assertThat(checks).hasValue(0);
            assertThat(proxy.execute(context("tenant-a", Map.of("query", "x"))).success()).isFalse();
            assertThat(checks).hasValue(1);
            assertThat(client.calls).hasValue(0);
        }
    }

    @Test
    void replacesResultAndSchemaMappingWithoutReimplementingTransport() {
        AgentToolRegistry registry = registry(List.of());
        AtomicInteger schemas = new AtomicInteger();
        McpToolSchemaAdapter customSchema = (server, remote, policy, reconciliation) -> {
            schemas.incrementAndGet();
            return new McpSchemaTranslator().translate(server, remote, policy, reconciliation);
        };
        McpToolResultAdapter customResult = (server, policy, result) ->
                AgentToolResult.success(Map.of("businessValue", "adapted"), null);
        try (McpConnectionManager manager = new McpConnectionManager(
                List.of(config(readPolicy())), registry,
                new QueueFactory(fake(schema("lookup", "string"))), customSchema,
                McpEventRecorder.NOOP, Clock.systemUTC(), McpToolAuthorizer.ALLOW, customResult)) {
            manager.start();
            assertThat(registry.require("docs__lookup")
                    .execute(context("tenant-a", Map.of("query", "x"))).output())
                    .containsEntry("businessValue", "adapted");
            assertThat(schemas).hasValue(1);
        }
    }

    @Test
    void closedManagerCannotReconnectOrRepublishTools() {
        AgentToolRegistry registry = registry(List.of());
        McpConnectionManager manager = manager(config(readPolicy()), registry,
                new QueueFactory(fake(schema("lookup", "string"))), McpEventRecorder.NOOP);
        manager.start();
        manager.close();
        manager.close();
        assertThat(registry.names()).isEmpty();
        assertThatThrownBy(() -> manager.refresh("docs"))
                .isInstanceOf(McpException.class).hasMessageContaining("MCP_MANAGER_CLOSED");
        assertThatThrownBy(manager::start).isInstanceOf(McpException.class);
    }

    @Test
    void alreadyCancelledCallDoesNotReconnectOrSendAnything() {
        AtomicInteger attempts = new AtomicInteger();
        CancellationToken token = new CancellationToken();
        token.cancel(io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause.USER_CANCEL);
        try (McpConnectionManager manager = manager(config(readPolicy()), registry(List.of()),
                server -> { attempts.incrementAndGet(); return fake(schema("lookup", "string")); },
                McpEventRecorder.NOOP)) {
            assertThatThrownBy(() -> manager.call("docs", "docs__lookup", "v1", "lookup",
                    Map.of(), Duration.ofSeconds(1), token))
                    .isInstanceOf(io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException.class);
            assertThat(attempts).hasValue(0);
        }
    }

    @Test
    void discoversCallsBulkUnregistersReconnectsAndRejectsStaleSchema() {
        AgentToolRegistry registry = registry(List.of(local("search")));
        FakeClient first = fake(schema("lookup", "string"));
        FakeClient changed = fake(schema("lookup", "integer"));
        FakeClient reconnected = fake(schema("lookup", "integer"));
        QueueFactory clients = new QueueFactory(first, changed, reconnected);
        List<McpEvent> events = new ArrayList<>();
        McpConnectionManager manager = manager(config(readPolicy()), registry, clients, events::add);

        manager.start();
        AgentTool old = registry.require("docs__lookup");
        String oldVersion = old.schema().version();
        AgentToolResult result = old.execute(context("tenant-a", Map.of("query", "one")));

        assertThat(result.success()).isTrue();
        assertThat(result.externalReceipt()).containsEntry("requestId", "remote-1");
        assertThat(first.calls.get()).isEqualTo(1);
        assertThat(registry.names()).contains("search", "docs__lookup");

        manager.refresh("docs");
        AgentTool current = registry.require("docs__lookup");
        assertThat(current.schema().version()).isNotEqualTo(oldVersion);
        assertThat(registry.require("docs__lookup", oldVersion)).isSameAs(old);
        assertThatThrownBy(() -> old.execute(context("tenant-a", Map.of("query", "one"))))
                .isInstanceOf(McpException.class)
                .extracting(error -> ((McpException) error).code())
                .isEqualTo("MCP_TOOL_VERSION_UNAVAILABLE");

        manager.disconnect("docs", "TEST_DISCONNECT");
        assertThat(registry.names()).containsExactly("search");
        manager.call(
                "docs", "docs__lookup", current.schema().version(), "lookup",
                Map.of("query", 1), Duration.ofSeconds(1), new CancellationToken());
        assertThat(registry.names()).contains("search", "docs__lookup");
        assertThat(events).anyMatch(event -> event.eventType() == McpEvent.Type.DISCOVERY
                && event.outcome() == McpEvent.Outcome.SUCCEEDED);
        manager.close();
    }

    @Test
    void appliesLocalTenantAndMutationReconciliationPolicy() {
        McpToolPolicy mutating = new McpToolPolicy(
                "publish", "policy-v1", AgentTool.Effect.MUTATING,
                true, Set.of("tenant-a"), Set.of("user-a"), "");
        AgentToolRegistry registry = registry(List.of());
        List<McpEvent> events = new ArrayList<>();
        McpConnectionManager manager = manager(
                config(mutating), registry, new QueueFactory(fake(schema("publish", "string"))),
                events::add);
        manager.start();
        McpProxyTool proxy = (McpProxyTool) registry.require("docs__publish");

        AgentToolResult denied = proxy.execute(context(
                "tenant-b", "user-a", Map.of("query", "x")));

        assertThat(proxy.effect()).isEqualTo(AgentTool.Effect.MUTATING);
        assertThat(proxy.approvalRequired()).isTrue();
        assertThat(denied.errorCode()).isEqualTo("MCP_TOOL_FORBIDDEN");
        assertThat(events).anyMatch(event -> event.outcome() == McpEvent.Outcome.REJECTED
                && "TENANT_POLICY_DENIED".equals(event.reason()));
        assertThat(proxy.reconcile(null, context("tenant-a", "user-a", Map.of())))
                .extracting(SideEffectReconciliation::resolution)
                .isEqualTo(SideEffectReconciliation.Resolution.UNKNOWN);
        manager.close();
    }

    @Test
    void nameCollisionFailsClosedWithoutRemovingLocalTools() {
        AgentToolRegistry registry = registry(List.of(local("docs__lookup")));
        McpConnectionManager manager = manager(
                config(readPolicy()), registry, new QueueFactory(fake(schema("lookup", "string"))),
                McpEventRecorder.NOOP);

        assertThatThrownBy(() -> manager.refresh("docs"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts across sources");
        assertThat(registry.names()).containsExactly("docs__lookup");
        assertThat(manager.health("docs").status())
                .isEqualTo(McpConnectionManager.Status.DISCONNECTED);
        manager.close();
    }

    @Test
    void opensCircuitAfterConsecutiveDiscoveryConnectionFailures() {
        AgentToolRegistry registry = registry(List.of());
        AtomicInteger attempts = new AtomicInteger();
        McpConnectionManager manager = manager(
                config(readPolicy()), registry, ignored -> {
                    attempts.incrementAndGet();
                    throw new McpException("MCP_CONNECTION_FAILED", true);
                }, McpEventRecorder.NOOP);

        assertThatThrownBy(() -> manager.refresh("docs")).isInstanceOf(McpException.class);
        assertThatThrownBy(() -> manager.refresh("docs")).isInstanceOf(McpException.class);

        assertThat(manager.health("docs").status())
                .isEqualTo(McpConnectionManager.Status.CIRCUIT_OPEN);
        assertThatThrownBy(() -> manager.call(
                "docs", "docs__lookup", "missing", "lookup", Map.of(),
                Duration.ofMillis(50), new CancellationToken()))
                .isInstanceOf(McpException.class)
                .extracting(error -> ((McpException) error).code())
                .isEqualTo("MCP_CIRCUIT_OPEN");
        assertThat(attempts.get()).isEqualTo(2);
        manager.close();
    }

    @Test
    void rejectsConcurrentCallsAtThePerServerBulkhead() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeClient blocking = new FakeClient(List.of(schema("lookup", "string"))) {
            @Override
            public McpCallResult callTool(
                    String remoteToolName,
                    Map<String, Object> arguments,
                    Duration timeout,
                    CancellationToken cancellationToken) {
                entered.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return super.callTool(remoteToolName, arguments, timeout, cancellationToken);
            }
        };
        AgentToolRegistry registry = registry(List.of());
        McpConnectionManager manager = manager(
                config(readPolicy()), registry, new QueueFactory(blocking), McpEventRecorder.NOOP);
        manager.start();
        String version = registry.require("docs__lookup").schema().version();
        CompletableFuture<McpCallResult> first = CompletableFuture.supplyAsync(() -> manager.call(
                "docs", "docs__lookup", version, "lookup", Map.of("query", "one"),
                Duration.ofSeconds(1), new CancellationToken()));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> manager.call(
                "docs", "docs__lookup", version, "lookup", Map.of("query", "two"),
                Duration.ofMillis(40), new CancellationToken()))
                .isInstanceOf(McpException.class)
                .extracting(error -> ((McpException) error).code())
                .isEqualTo("MCP_SERVER_SATURATED");
        release.countDown();
        assertThat(first.get(1, TimeUnit.SECONDS).error()).isFalse();
        manager.close();
    }

    private static McpConnectionManager manager(
            McpServerConfig config,
            AgentToolRegistry registry,
            McpClientFactory clients,
            McpEventRecorder events) {
        return new McpConnectionManager(
                List.of(config), registry, clients,
                new McpSchemaTranslator(new ObjectMapper()), events, Clock.systemUTC());
    }

    private static AgentToolRegistry registry(List<AgentTool> localTools) {
        return new AgentToolRegistry(
                localTools,
                tool -> tool.effect() != AgentTool.Effect.MUTATING
                        || tool instanceof McpProxyTool);
    }

    private static McpServerConfig config(McpToolPolicy policy) {
        return new McpServerConfig(
                1, "docs", "config-v1", McpServerConfig.Transport.STREAMABLE_HTTP,
                URI.create("http://127.0.0.1:9999/mcp"), "",
                McpServerConfig.SUPPORTED_PROTOCOL_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1, 2,
                Duration.ofSeconds(1), 4096, Map.of(policy.remoteToolName(), policy));
    }

    private static McpToolPolicy readPolicy() {
        return new McpToolPolicy(
                "lookup", "policy-v1", AgentTool.Effect.READ_ONLY,
                false, Set.of("tenant-a"), "");
    }

    private static McpRemoteTool schema(String name, String queryType) {
        return new McpRemoteTool(
                name, name, Map.of(
                        "type", "object",
                        "properties", Map.of("query", Map.of("type", queryType)),
                        "required", List.of("query")));
    }

    private static FakeClient fake(McpRemoteTool tool) {
        return new FakeClient(List.of(tool));
    }

    private static AgentToolContext context(String tenant, Map<String, Object> arguments) {
        return context(tenant, null, arguments);
    }

    private static AgentToolContext context(
            String tenant, String user, Map<String, Object> arguments) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("tenantId", tenant);
        if (user != null) {
            attributes.put("userId", user);
        }
        return new AgentToolContext(
                "run-1", "call-1", "idem-1",
                new AgentRunRequest(
                        "request-1", "session-1", "turn-1", "input",
                        Map.copyOf(attributes)),
                arguments, Duration.ofSeconds(2), new CancellationToken());
    }

    private static AgentTool local(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema("local-v1", Map.of());
            }

            @Override
            public Effect effect() {
                return Effect.READ_ONLY;
            }

            @Override
            public AgentToolResult execute(AgentToolContext context) {
                return AgentToolResult.success(Map.of(), null);
            }
        };
    }

    private static final class QueueFactory implements McpClientFactory {
        private final Queue<McpClient> clients = new ArrayDeque<>();

        private QueueFactory(McpClient... clients) {
            this.clients.addAll(List.of(clients));
        }

        @Override
        public McpClient create(McpServerConfig config) {
            McpClient client = clients.poll();
            if (client == null) {
                throw new AssertionError("unexpected MCP reconnect");
            }
            return client;
        }
    }

    private static class FakeClient implements McpClient {
        private final List<McpRemoteTool> tools;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeClient(List<McpRemoteTool> tools) {
            this.tools = tools;
        }

        @Override
        public List<McpRemoteTool> initializeAndList(CancellationToken cancellationToken) {
            return tools;
        }

        @Override
        public McpCallResult callTool(
                String remoteToolName,
                Map<String, Object> arguments,
                Duration timeout,
                CancellationToken cancellationToken) {
            calls.incrementAndGet();
            return new McpCallResult(false, Map.of(
                    "structuredContent", Map.of("tool", remoteToolName),
                    "_meta", Map.of("externalReceipt", Map.of("requestId", "remote-1"))));
        }

        @Override
        public void close() {
        }
    }
}
