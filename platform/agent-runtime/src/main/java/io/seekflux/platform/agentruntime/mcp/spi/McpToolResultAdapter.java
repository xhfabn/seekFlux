package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.mcp.infrastructure.tool.DefaultMcpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;

import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import java.util.Map;

/** Business receipt/status conventions are not MCP protocol guarantees. */
public interface McpToolResultAdapter {

    McpToolResultAdapter DEFAULT = new DefaultMcpToolResultAdapter();

    AgentToolResult convert(McpServerConfig server, McpToolPolicy policy, McpCallResult result);

    /** Optional business argument mapping, e.g. a server-supported idempotency key field. */
    default Map<String, Object> callArguments(
            McpServerConfig server, McpToolPolicy policy, AgentToolContext context) {
        return context.arguments();
    }

    default Map<String, Object> reconciliationArguments(
            McpServerConfig server, McpToolPolicy policy, String idempotencyKey, String toolCallId) {
        return Map.of("idempotencyKey", idempotencyKey, "toolCallId", toolCallId);
    }

    default SideEffectReconciliation reconcile(
            McpServerConfig server, McpToolPolicy policy, McpCallResult result) {
        return SideEffectReconciliation.unknown(
                "MCP_EXTERNAL_STATUS_QUERY", "no business status mapping is available");
    }
}
