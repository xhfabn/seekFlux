package io.seekflux.platform.agentruntime.mcp.infrastructure.tool;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.model.McpTranslatedTool;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolCallGateway;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolReconciler;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import java.util.Map;

public final class McpProxyTool implements AgentTool, AgentToolReconciler {

    private final McpServerConfig server;
    private final McpToolPolicy policy;
    private final McpTranslatedTool translated;
    private final McpToolCallGateway connections;
    private final McpToolAuthorizer authorizer;
    private final McpToolResultAdapter results;

    public McpProxyTool(
            McpServerConfig server,
            McpToolPolicy policy,
            McpTranslatedTool translated,
            McpToolCallGateway connections) {
        this(server, policy, translated, connections,
                McpToolAuthorizer.ALLOW, McpToolResultAdapter.DEFAULT);
    }

    public McpProxyTool(
            McpServerConfig server,
            McpToolPolicy policy,
            McpTranslatedTool translated,
            McpToolCallGateway connections,
            McpToolAuthorizer authorizer,
            McpToolResultAdapter results) {
        this.server = server;
        this.policy = policy;
        this.translated = translated;
        this.connections = connections;
        this.authorizer = java.util.Objects.requireNonNull(authorizer);
        this.results = java.util.Objects.requireNonNull(results);
    }

    @Override
    public String name() {
        return policy.localName(server.serverId());
    }

    @Override
    public AgentToolSchema schema() {
        return translated.schema();
    }

    @Override
    public Effect effect() {
        return policy.effect();
    }

    @Override
    public String source() {
        return server.source();
    }

    @Override
    public boolean approvalRequired() {
        return policy.approvalRequired();
    }

    @Override
    public String approvalReason() {
        return "MCP Tool requires local approval: " + name();
    }

    @Override
    public AgentToolResult execute(AgentToolContext context) {
        if (!authorized(context)) {
            connections.recordPolicyRejection(
                    server.serverId(), name(), "TENANT_POLICY_DENIED");
            return AgentToolResult.failure("MCP_TOOL_FORBIDDEN");
        }
        McpCallResult result = connections.call(
                server.serverId(),
                name(),
                schema().version(),
                policy.remoteToolName(),
                results.callArguments(server, policy, context),
                context.remaining(),
                context.cancellationToken());
        return results.convert(server, policy, result);
    }

    @Override
    public SideEffectReconciliation reconcile(
            SideEffectLedgerEntry ledgerEntry,
            AgentToolContext context) {
        if (effect() != Effect.MUTATING || policy.reconciliationToolName().isBlank()) {
            return SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "no local reconciliation Tool is configured");
        }
        if (!authorized(context)) {
            return SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "tenant is no longer authorized");
        }
        Map<String, Object> arguments = results.reconciliationArguments(
                server, policy, ledgerEntry.idempotencyKey(), ledgerEntry.toolCallId());
        McpCallResult result;
        try {
            result = connections.call(
                    server.serverId(),
                    name(),
                    schema().version(),
                    policy.reconciliationToolName(),
                    Map.copyOf(arguments),
                    context.remaining(),
                    context.cancellationToken());
        } catch (McpException unavailable) {
            return SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", unavailable.code());
        }
        if (result.error()) {
            return SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "remote status query failed");
        }
        return results.reconcile(server, policy, result);
    }

    public McpToolPolicy policy() {
        return policy;
    }

    private boolean authorized(AgentToolContext context) {
        return McpToolAuthorizer.LOCAL_ALLOWLIST.authorized(server, policy, context)
                && authorizer.authorized(server, policy, context);
    }
}
