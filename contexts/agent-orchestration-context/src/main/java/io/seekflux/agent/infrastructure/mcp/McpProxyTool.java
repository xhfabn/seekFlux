package io.seekflux.agent.infrastructure.mcp;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolReconciler;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpProxyTool implements AgentTool, AgentToolReconciler {

    private final McpServerConfig server;
    private final McpToolPolicy policy;
    private final McpSchemaTranslator.TranslatedTool translated;
    private final McpConnectionManager connections;

    public McpProxyTool(
            McpServerConfig server,
            McpToolPolicy policy,
            McpSchemaTranslator.TranslatedTool translated,
            McpConnectionManager connections) {
        this.server = server;
        this.policy = policy;
        this.translated = translated;
        this.connections = connections;
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
                context.arguments(),
                context.remaining(),
                context.cancellationToken());
        if (result.error()) {
            return AgentToolResult.failure("MCP_TOOL_REPORTED_ERROR", receipt(result.result()));
        }
        return AgentToolResult.success(output(result.result()), null, receipt(result.result()));
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
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("idempotencyKey", ledgerEntry.idempotencyKey());
        arguments.put("toolCallId", ledgerEntry.toolCallId());
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
        Object rawStatus = structured(result.result()).get("status");
        String status = rawStatus == null ? "UNKNOWN" : String.valueOf(rawStatus);
        AgentToolResult toolResult = AgentToolResult.success(
                output(result.result()), null, receipt(result.result()));
        return switch (status) {
            case "SUCCEEDED" -> SideEffectReconciliation.succeeded(
                    toolResult, "MCP_EXTERNAL_STATUS_QUERY");
            case "FAILED" -> SideEffectReconciliation.failed(
                    AgentToolResult.failure("MCP_MUTATION_FAILED", receipt(result.result())),
                    "MCP_EXTERNAL_STATUS_QUERY");
            default -> SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "remote status remains unknown");
        };
    }

    public McpToolPolicy policy() {
        return policy;
    }

    private boolean authorized(AgentToolContext context) {
        Object tenant = context.request().attributes().get("tenantId");
        if (!policy.allowedTenantIds().isEmpty()
                && (!(tenant instanceof String value)
                        || !policy.allowedTenantIds().contains(value))) {
            return false;
        }
        Object user = context.request().attributes().get("userId");
        return policy.allowedUserIds().isEmpty()
                || user instanceof String value && policy.allowedUserIds().contains(value);
    }

    private static Map<String, Object> output(Map<String, Object> result) {
        Map<String, Object> output = new LinkedHashMap<>();
        Object content = result.get("content");
        if (content instanceof List<?> values) {
            output.put("content", List.copyOf(values));
        }
        Map<String, Object> structured = structured(result);
        if (!structured.isEmpty()) {
            output.put("structuredContent", structured);
        }
        return Map.copyOf(output);
    }

    private static Map<String, Object> receipt(Map<String, Object> result) {
        Object rawMeta = result.get("_meta");
        if (!(rawMeta instanceof Map<?, ?> metadata)) {
            return Map.of();
        }
        Object rawReceipt = metadata.get("externalReceipt");
        if (!(rawReceipt instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> receipt = new LinkedHashMap<>();
        values.forEach((key, value) -> receipt.put(String.valueOf(key), value));
        return Map.copyOf(receipt);
    }

    private static Map<String, Object> structured(Map<String, Object> result) {
        Object raw = result.get("structuredContent");
        if (!(raw instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> structured = new LinkedHashMap<>();
        values.forEach((key, value) -> structured.put(String.valueOf(key), value));
        return Map.copyOf(structured);
    }
}
