package io.seekflux.platform.agentruntime.mcp.infrastructure.tool;

import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;

import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standard content mapping with optional SeekFlux receipt/status conventions, not MCP guarantees. */
public final class DefaultMcpToolResultAdapter implements McpToolResultAdapter {

    @Override
    public AgentToolResult convert(
            McpServerConfig server, McpToolPolicy policy, McpCallResult result) {
        if (result.error()) {
            return AgentToolResult.failure("MCP_TOOL_REPORTED_ERROR", receipt(result.result()));
        }
        return AgentToolResult.success(output(result.result()), null, receipt(result.result()));
    }

    @Override
    public SideEffectReconciliation reconcile(
            McpServerConfig server, McpToolPolicy policy, McpCallResult result) {
        if (result.error()) {
            return SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "remote status query failed");
        }
        Object status = structured(result.result()).get("status");
        return switch (status == null ? "UNKNOWN" : String.valueOf(status)) {
            case "SUCCEEDED" -> SideEffectReconciliation.succeeded(
                    convert(server, policy, result), "MCP_EXTERNAL_STATUS_QUERY");
            case "FAILED" -> SideEffectReconciliation.failed(
                    AgentToolResult.failure("MCP_MUTATION_FAILED", receipt(result.result())),
                    "MCP_EXTERNAL_STATUS_QUERY");
            default -> SideEffectReconciliation.unknown(
                    "MCP_EXTERNAL_STATUS_QUERY", "remote status remains unknown");
        };
    }

    private static Map<String, Object> output(Map<String, Object> result) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (result.get("content") instanceof List<?> values) {
            output.put("content", List.copyOf(values));
        }
        Map<String, Object> structured = structured(result);
        if (!structured.isEmpty()) {
            output.put("structuredContent", structured);
        }
        return Map.copyOf(output);
    }

    private static Map<String, Object> receipt(Map<String, Object> result) {
        return result.get("_meta") instanceof Map<?, ?> metadata
                ? objectMap(metadata.get("externalReceipt")) : Map.of();
    }

    private static Map<String, Object> structured(Map<String, Object> result) {
        return objectMap(result.get("structuredContent"));
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return Collections.unmodifiableMap(copy);
    }
}
