package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;

import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;

/** Additional host authorization. A custom policy cannot bypass configured local allowlists. */
@FunctionalInterface
public interface McpToolAuthorizer {

    McpToolAuthorizer ALLOW = (server, policy, context) -> true;

    McpToolAuthorizer LOCAL_ALLOWLIST = (server, policy, context) -> {
        Object tenant = context.request().attributes().get("tenantId");
        Object user = context.request().attributes().get("userId");
        return (policy.allowedTenantIds().isEmpty()
                || tenant instanceof String value && policy.allowedTenantIds().contains(value))
                && (policy.allowedUserIds().isEmpty()
                || user instanceof String value && policy.allowedUserIds().contains(value));
    };

    boolean authorized(McpServerConfig server, McpToolPolicy policy, AgentToolContext context);
}
