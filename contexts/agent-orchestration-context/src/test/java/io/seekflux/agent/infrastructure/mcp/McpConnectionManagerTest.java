package io.seekflux.agent.infrastructure.mcp;

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
