package io.seekflux.platform.agentruntime.mcp.connection;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClientFactory;
import io.seekflux.platform.agentruntime.mcp.infrastructure.schema.McpSchemaTranslator;
import io.seekflux.platform.agentruntime.mcp.infrastructure.tool.McpProxyTool;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpEvent;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.model.McpTranslatedTool;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolCallGateway;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.spi.McpEventRecorder;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolSchemaAdapter;

import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpConnectionManager implements McpToolCallGateway, AutoCloseable {

    private final Map<String, ServerRuntime> servers;
    private final AgentToolRegistry registry;
    private final McpClientFactory clients;
    private final McpToolSchemaAdapter schemas;
    private final McpToolAuthorizer authorizer;
    private final McpToolResultAdapter results;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final McpEventRecorder events;
    private final Clock clock;

    public McpConnectionManager(Collection<McpServerConfig> configs, AgentToolRegistry registry) {
        this(configs, registry, new StreamableHttpMcpClientFactory(), new McpSchemaTranslator(),
                McpEventRecorder.NOOP, Clock.systemUTC());
    }

    public McpConnectionManager(
            Collection<McpServerConfig> configs,
            AgentToolRegistry registry,
            McpClientFactory clients,
            McpToolSchemaAdapter schemas,
            McpEventRecorder events,
            Clock clock) {
        this(configs, registry, clients, schemas, events, clock,
                McpToolAuthorizer.ALLOW, McpToolResultAdapter.DEFAULT);
    }

    public McpConnectionManager(
            Collection<McpServerConfig> configs,
            AgentToolRegistry registry,
            McpClientFactory clients,
            McpToolSchemaAdapter schemas,
            McpEventRecorder events,
            Clock clock,
            McpToolAuthorizer authorizer,
            McpToolResultAdapter results) {
        this.registry = java.util.Objects.requireNonNull(registry, "Tool registry is required");
        this.clients = java.util.Objects.requireNonNull(clients, "MCP client factory is required");
        this.schemas = java.util.Objects.requireNonNull(schemas, "MCP schema translator is required");
        this.authorizer = java.util.Objects.requireNonNull(authorizer, "MCP authorizer is required");
        this.results = java.util.Objects.requireNonNull(results, "MCP result adapter is required");
        this.events = events == null ? McpEventRecorder.NOOP : events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        Map<String, ServerRuntime> indexed = new LinkedHashMap<>();
        if (configs != null) {
            for (McpServerConfig config : configs) {
                if (indexed.size() >= 32) {
                    throw new IllegalArgumentException("MCP server count must not exceed 32");
                }
                ServerRuntime previous = indexed.put(
                        config.serverId(), new ServerRuntime(config));
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate MCP server id: " + config.serverId());
                }
            }
        }
        this.servers = Map.copyOf(indexed);
    }

    public void start() {
        requireOpen();
        for (String serverId : servers.keySet()) {
            try {
                refresh(serverId);
            } catch (RuntimeException ignored) {
                // Startup is fail-closed per source; local Tools and other MCP servers stay usable.
            }
        }
    }

    public ServerHealth refresh(String serverId) {
        return refresh(serverId, new CancellationToken());
    }

    private ServerHealth refresh(String serverId, CancellationToken token) {
        ServerRuntime runtime = require(serverId);
        synchronized (runtime.refreshLock) {
            requireOpen();
            token.throwIfCancelled();
            return refresh(runtime, token);
        }
    }

    private ServerHealth refresh(ServerRuntime runtime, CancellationToken token) {
        long started = System.nanoTime();
        boolean reconnect;
        synchronized (runtime) {
            reconnect = !"NOT_STARTED".equals(runtime.lastReason);
        }
        McpClient next = null;
        try {
            next = clients.create(runtime.config);
            List<McpRemoteTool> discovered = next.initializeAndList(token);
            Map<String, McpRemoteTool> remote = indexRemote(discovered);
            List<McpProxyTool> proxies = new ArrayList<>();
            Map<String, String> versions = new LinkedHashMap<>();
            for (McpToolPolicy policy : runtime.config.toolPolicies().values()) {
                McpRemoteTool primary = remote.get(policy.remoteToolName());
                if (primary == null) {
                    throw new McpException("MCP_CONFIGURED_TOOL_MISSING", false);
                }
                McpTranslatedTool translated;
                try {
                    String reconciliationHash = null;
                    if (!policy.reconciliationToolName().isBlank()) {
                        McpRemoteTool reconciliation = remote.get(
                                policy.reconciliationToolName());
                        if (reconciliation == null) {
                            throw new McpException(
                                    "MCP_RECONCILIATION_TOOL_MISSING", false);
                        }
                        McpToolPolicy reconciliationPolicy = new McpToolPolicy(
                                reconciliation.name(),
                                policy.policyVersion(),
                                io.seekflux.platform.agentruntime.application.spi.business.tool
                                        .AgentTool.Effect.READ_ONLY,
                                false,
                                policy.allowedTenantIds(),
                                policy.allowedUserIds(),
                                "");
                        McpTranslatedTool statusTool = schemas.translate(
                                runtime.config, reconciliation, reconciliationPolicy, null);
                        statusTool.schema().validate(results.reconciliationArguments(
                                runtime.config, policy, "validation", "validation"));
                        reconciliationHash = statusTool.remoteSchemaHash();
                    }
                    translated = schemas.translate(
                            runtime.config, primary, policy, reconciliationHash);
                } catch (McpException known) {
                    throw known;
                } catch (IllegalArgumentException rejectedSchema) {
                    throw new McpException(
                            "MCP_SCHEMA_REJECTED", false, rejectedSchema);
                }
                McpProxyTool proxy = new McpProxyTool(
                        runtime.config, policy, translated, this, authorizer, results);
                proxies.add(proxy);
                versions.put(proxy.name(), proxy.schema().version());
            }
            registry.replaceSource(runtime.config.source(), proxies);
            synchronized (runtime) {
                McpClient previous = runtime.client;
                runtime.client = next;
                runtime.toolVersions = Map.copyOf(versions);
                runtime.status = Status.CONNECTED;
                runtime.consecutiveFailures = 0;
                runtime.circuitOpenUntil = null;
                runtime.lastReason = "CONNECTED";
                runtime.lastChangedAt = clock.instant();
                if (previous != null && previous != next) {
                    runtime.retired.add(previous);
                }
                closeRetiredIfIdle(runtime);
            }
            record(McpEvent.Type.DISCOVERY, runtime, null, McpEvent.Outcome.SUCCEEDED,
                    "CONNECTED", elapsedMillis(started), proxies.size());
            record(McpEvent.Type.CONNECTION, runtime, null, McpEvent.Outcome.SUCCEEDED,
                    reconnect ? "RECONNECTED" : "CONNECTED",
                    elapsedMillis(started), proxies.size());
            return health(runtime.config.serverId());
        } catch (RuntimeException failure) {
            closeQuietly(next);
            String reason = failure instanceof McpException mcp
                    ? mcp.code() : "MCP_DISCOVERY_FAILED";
            markDisconnected(runtime, reason, failure instanceof McpException mcp
                    && mcp.connectionFailure());
            record(McpEvent.Type.DISCOVERY, runtime, null, McpEvent.Outcome.FAILED,
                    reason, elapsedMillis(started), 0);
            throw failure;
        }
    }

    public void disconnect(String serverId, String reason) {
        ServerRuntime runtime = require(serverId);
        markDisconnected(runtime,
                reason == null || reason.isBlank() ? "DISCONNECTED" : reason, false);
    }

    public McpCallResult call(
            String serverId,
            String localToolName,
            String expectedSchemaVersion,
            String remoteToolName,
            Map<String, Object> arguments,
            Duration remaining,
            CancellationToken cancellationToken) {
        requireOpen();
        cancellationToken.throwIfCancelled();
        ServerRuntime runtime = require(serverId);
        if (remaining != null && (remaining.isZero() || remaining.isNegative())) {
            throw new McpException("MCP_REQUEST_TIMEOUT", false);
        }
        Duration timeout = smaller(remaining, runtime.config.requestTimeout());
        long deadline = System.nanoTime() + timeout.toNanos();
        ensureConnected(runtime, cancellationToken);
        acquire(runtime, remainingBudget(deadline), cancellationToken);
        McpClient client;
        synchronized (runtime) {
            if (runtime.status != Status.CONNECTED
                    || runtime.client == null
                    || !expectedSchemaVersion.equals(runtime.toolVersions.get(localToolName))) {
                runtime.bulkhead.release();
                throw new McpException("MCP_TOOL_VERSION_UNAVAILABLE", false);
            }
            runtime.inFlight.incrementAndGet();
            client = runtime.client;
        }
        long started = System.nanoTime();
        try {
            McpCallResult result = client.callTool(
                    remoteToolName, arguments, remainingBudget(deadline), cancellationToken);
            record(McpEvent.Type.CALL, runtime, localToolName,
                    result.error() ? McpEvent.Outcome.REJECTED : McpEvent.Outcome.SUCCEEDED,
                    result.error() ? "REMOTE_TOOL_ERROR" : "SUCCEEDED",
                    elapsedMillis(started), runtime.toolVersions.size());
            return result;
        } catch (AgentCancellationException cancelled) {
            record(McpEvent.Type.CALL, runtime, localToolName, McpEvent.Outcome.CANCELLED,
                    cancelled.cancellationCause().name(), elapsedMillis(started),
                    runtime.toolVersions.size());
            throw cancelled;
        } catch (RuntimeException failure) {
            String reason = failure instanceof McpException mcp
                    ? mcp.code() : "MCP_CALL_FAILED";
            boolean connectionFailure = failure instanceof McpException mcp
                    && mcp.connectionFailure();
            if (connectionFailure) {
                markDisconnected(runtime, reason, true);
            }
            record(McpEvent.Type.CALL, runtime, localToolName, McpEvent.Outcome.FAILED,
                    reason, elapsedMillis(started), runtime.toolVersions.size());
            throw failure;
        } finally {
            runtime.inFlight.decrementAndGet();
            runtime.bulkhead.release();
            synchronized (runtime) {
                closeRetiredIfIdle(runtime);
            }
        }
    }

    public ServerHealth health(String serverId) {
        ServerRuntime runtime = require(serverId);
        synchronized (runtime) {
            return new ServerHealth(
                    runtime.config.serverId(),
                    runtime.config.configVersion(),
                    runtime.status,
                    runtime.toolVersions.size(),
                    runtime.toolVersions,
                    runtime.inFlight.get(),
                    runtime.lastReason,
                    runtime.lastChangedAt,
                    runtime.circuitOpenUntil);
        }
    }

    public Map<String, ServerHealth> health() {
        Map<String, ServerHealth> result = new LinkedHashMap<>();
        servers.keySet().forEach(id -> result.put(id, health(id)));
        return Map.copyOf(result);
    }

    @Override
    public void recordPolicyRejection(String serverId, String localToolName, String reason) {
        ServerRuntime runtime = require(serverId);
        record(McpEvent.Type.CALL, runtime, localToolName, McpEvent.Outcome.REJECTED,
                reason, 0, runtime.toolVersions.size());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        servers.values().forEach(runtime -> {
            synchronized (runtime.refreshLock) {
                registry.unregisterSource(runtime.config.source());
                synchronized (runtime) {
                    closeQuietly(runtime.client);
                    runtime.client = null;
                    runtime.retired.forEach(McpConnectionManager::closeQuietly);
                    runtime.retired.clear();
                    runtime.status = Status.DISCONNECTED;
                    runtime.toolVersions = Map.of();
                    runtime.lastReason = "CLOSED";
                    runtime.lastChangedAt = clock.instant();
                }
            }
        });
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new McpException("MCP_MANAGER_CLOSED", false);
        }
    }

    private void ensureConnected(ServerRuntime runtime, CancellationToken token) {
        synchronized (runtime) {
            if (runtime.status == Status.CONNECTED && runtime.client != null) {
                return;
            }
            if (runtime.status == Status.CIRCUIT_OPEN
                    && runtime.circuitOpenUntil != null
                    && clock.instant().isBefore(runtime.circuitOpenUntil)) {
                throw new McpException("MCP_CIRCUIT_OPEN", true);
            }
        }
        refresh(runtime.config.serverId(), token);
    }

    private static Duration remainingBudget(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new McpException("MCP_REQUEST_TIMEOUT", false);
        }
        return Duration.ofNanos(remaining);
    }

    private void acquire(
            ServerRuntime runtime,
            Duration timeout,
            CancellationToken cancellationToken) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                cancellationToken.throwIfCancelled();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new McpException("MCP_SERVER_SATURATED", false);
                }
                if (runtime.bulkhead.tryAcquire(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                        TimeUnit.NANOSECONDS)) {
                    return;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP_BULKHEAD_INTERRUPTED", false, interrupted);
        }
    }

    private void markDisconnected(
            ServerRuntime runtime,
            String reason,
            boolean countForCircuit) {
        registry.unregisterSource(runtime.config.source());
        synchronized (runtime) {
            McpClient previous = runtime.client;
            runtime.client = null;
            runtime.toolVersions = Map.of();
            runtime.lastReason = reason;
            runtime.lastChangedAt = clock.instant();
            if (previous != null) {
                runtime.retired.add(previous);
            }
            if (countForCircuit) {
                runtime.consecutiveFailures++;
            }
            if (runtime.consecutiveFailures >= runtime.config.circuitFailureThreshold()) {
                runtime.status = Status.CIRCUIT_OPEN;
                runtime.circuitOpenUntil = clock.instant().plus(
                        runtime.config.circuitOpenDuration());
            } else {
                runtime.status = Status.DISCONNECTED;
            }
            closeRetiredIfIdle(runtime);
        }
        record(McpEvent.Type.CONNECTION, runtime, null, McpEvent.Outcome.FAILED,
                reason, 0, 0);
    }

    private static Map<String, McpRemoteTool> indexRemote(List<McpRemoteTool> tools) {
        Map<String, McpRemoteTool> result = new LinkedHashMap<>();
        for (McpRemoteTool tool : tools) {
            McpRemoteTool previous = result.put(tool.name(), tool);
            if (previous != null) {
                throw new McpException("MCP_DUPLICATE_REMOTE_TOOL", false);
            }
        }
        return Map.copyOf(result);
    }

    private ServerRuntime require(String serverId) {
        ServerRuntime runtime = servers.get(serverId);
        if (runtime == null) {
            throw new IllegalArgumentException("unknown MCP server: " + serverId);
        }
        return runtime;
    }

    private void record(
            McpEvent.Type type,
            ServerRuntime runtime,
            String tool,
            McpEvent.Outcome outcome,
            String reason,
            long duration,
            int activeTools) {
        try {
            events.record(new McpEvent(
                    1, type, runtime.config.serverId(), tool, outcome, reason,
                    duration, activeTools, clock.instant()));
        } catch (RuntimeException ignored) {
            // Observability cannot alter MCP discovery or execution semantics.
        }
    }

    private static Duration smaller(Duration left, Duration right) {
        if (left == null || left.isZero() || left.isNegative()) {
            return right;
        }
        return left.compareTo(right) < 0 ? left : right;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
    }

    private static void closeRetiredIfIdle(ServerRuntime runtime) {
        if (runtime.inFlight.get() == 0) {
            runtime.retired.forEach(McpConnectionManager::closeQuietly);
            runtime.retired.clear();
        }
    }

    private static void closeQuietly(McpClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // Best effort.
            }
        }
    }

    public enum Status {
        DISCONNECTED,
        CONNECTED,
        CIRCUIT_OPEN
    }

    public record ServerHealth(
            String serverId,
            String configVersion,
            Status status,
            int activeToolCount,
            Map<String, String> toolVersions,
            int inFlightCalls,
            String lastReason,
            Instant lastChangedAt,
            Instant circuitOpenUntil) {

        public ServerHealth {
            toolVersions = toolVersions == null ? Map.of() : Map.copyOf(toolVersions);
        }
    }

    private static final class ServerRuntime {
        private final McpServerConfig config;
        private final Semaphore bulkhead;
        private final Object refreshLock = new Object();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final List<McpClient> retired = new ArrayList<>();
        private McpClient client;
        private Map<String, String> toolVersions = Map.of();
        private Status status = Status.DISCONNECTED;
        private int consecutiveFailures;
        private Instant circuitOpenUntil;
        private String lastReason = "NOT_STARTED";
        private Instant lastChangedAt = Instant.EPOCH;

        private ServerRuntime(McpServerConfig config) {
            this.config = config;
            this.bulkhead = new Semaphore(config.maxConcurrentCalls());
        }
    }
}
