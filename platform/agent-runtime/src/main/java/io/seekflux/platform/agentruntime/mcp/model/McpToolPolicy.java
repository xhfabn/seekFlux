package io.seekflux.platform.agentruntime.mcp.model;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import java.util.Set;

public record McpToolPolicy(
        String remoteToolName,
        String policyVersion,
        AgentTool.Effect effect,
        boolean approvalRequired,
        Set<String> allowedTenantIds,
        Set<String> allowedUserIds,
        String reconciliationToolName) {

    public McpToolPolicy(
            String remoteToolName,
            String policyVersion,
            AgentTool.Effect effect,
            boolean approvalRequired,
            Set<String> allowedTenantIds,
            String reconciliationToolName) {
        this(remoteToolName, policyVersion, effect, approvalRequired,
                allowedTenantIds, Set.of(), reconciliationToolName);
    }

    public McpToolPolicy {
        remoteToolName = toolName(remoteToolName, "MCP remote Tool name");
        policyVersion = McpServerConfig.text(policyVersion, "MCP Tool policy version", 128);
        if (effect == null) {
            throw new IllegalArgumentException("MCP Tool effect must be locally configured");
        }
        allowedTenantIds = allowedTenantIds == null ? Set.of() : Set.copyOf(allowedTenantIds);
        if (allowedTenantIds.size() > 256
                || allowedTenantIds.stream().anyMatch(value -> value == null
                        || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("MCP tenant allowlist is invalid");
        }
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        if (allowedUserIds.size() > 256
                || allowedUserIds.stream().anyMatch(value -> value == null
                        || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("MCP user allowlist is invalid");
        }
        reconciliationToolName = reconciliationToolName == null
                ? "" : reconciliationToolName.trim();
        if (!reconciliationToolName.isEmpty()) {
            reconciliationToolName = toolName(
                    reconciliationToolName, "MCP reconciliation Tool name");
        }
        if (effect != AgentTool.Effect.MUTATING && !reconciliationToolName.isEmpty()) {
            throw new IllegalArgumentException(
                    "only a MUTATING MCP Tool can configure reconciliation");
        }
    }

    public String localName(String serverId) {
        String value = McpServerConfig.identifier(serverId, "MCP server id")
                + "__" + remoteToolName;
        if (value.length() > 128) {
            throw new IllegalArgumentException("namespaced MCP Tool name exceeds 128 characters");
        }
        return value;
    }

    private static String toolName(String value, String name) {
        String normalized = McpServerConfig.text(value, name, 128);
        if (!normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }
}
