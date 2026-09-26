package io.seekflux.apps.agentserver.bootstrap;

import io.seekflux.agent.infrastructure.mcp.McpServerConfig;
import io.seekflux.agent.infrastructure.mcp.McpToolPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seekflux.agent.mcp")
record AgentMcpProperties(boolean enabled, List<Server> servers) {

    AgentMcpProperties {
        servers = servers == null ? List.of() : List.copyOf(servers);
        if (servers.size() > 32) {
            throw new IllegalArgumentException("MCP server count must not exceed 32");
        }
    }

    List<McpServerConfig> runtimeConfigs() {
        if (!enabled) {
            return List.of();
        }
        return servers.stream().map(Server::runtimeConfig).toList();
    }

    Set<String> configuredToolNames() {
        if (!enabled) {
            return Set.of();
        }
        return servers.stream()
                .flatMap(server -> server.tools().stream()
                        .map(tool -> tool.policy().localName(server.id())))
                .collect(Collectors.toUnmodifiableSet());
    }

    record Server(
            String id,
            String configVersion,
            URI endpoint,
            String credentialRef,
            Duration connectTimeout,
            Duration requestTimeout,
            Integer maxConcurrentCalls,
            Integer circuitFailureThreshold,
            Duration circuitOpenDuration,
            Integer maxResponseBytes,
            List<Tool> tools) {

        Server {
            configVersion = defaultText(configVersion, "mcp-config-v1");
            credentialRef = credentialRef == null ? "" : credentialRef;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
            maxConcurrentCalls = maxConcurrentCalls == null ? 8 : maxConcurrentCalls;
            circuitFailureThreshold = circuitFailureThreshold == null
                    ? 3 : circuitFailureThreshold;
            circuitOpenDuration = circuitOpenDuration == null
                    ? Duration.ofSeconds(30) : circuitOpenDuration;
            maxResponseBytes = maxResponseBytes == null ? 1024 * 1024 : maxResponseBytes;
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        McpServerConfig runtimeConfig() {
            Map<String, McpToolPolicy> policies = new LinkedHashMap<>();
            for (Tool tool : tools) {
                McpToolPolicy policy = tool.policy();
                if (policies.put(policy.remoteToolName(), policy) != null) {
                    throw new IllegalArgumentException(
                            "duplicate MCP Tool policy: " + policy.remoteToolName());
                }
            }
            return new McpServerConfig(
                    1,
                    id,
                    configVersion,
                    McpServerConfig.Transport.STREAMABLE_HTTP,
                    endpoint,
                    credentialRef,
                    McpServerConfig.SUPPORTED_PROTOCOL_VERSION,
                    connectTimeout,
                    requestTimeout,
                    maxConcurrentCalls,
                    circuitFailureThreshold,
                    circuitOpenDuration,
                    maxResponseBytes,
                    Map.copyOf(policies));
        }
    }

    record Tool(
            String remoteName,
            String policyVersion,
            AgentTool.Effect effect,
            boolean approvalRequired,
            Set<String> allowedTenantIds,
            Set<String> allowedUserIds,
            String reconciliationToolName) {

        Tool {
            policyVersion = defaultText(policyVersion, "mcp-tool-policy-v1");
            allowedTenantIds = allowedTenantIds == null
                    ? Set.of() : Set.copyOf(allowedTenantIds);
            allowedUserIds = allowedUserIds == null
                    ? Set.of() : Set.copyOf(allowedUserIds);
            reconciliationToolName = reconciliationToolName == null
                    ? "" : reconciliationToolName;
        }

        McpToolPolicy policy() {
            return new McpToolPolicy(
                    remoteName,
                    policyVersion,
                    effect,
                    approvalRequired,
                    allowedTenantIds,
                    allowedUserIds,
                    reconciliationToolName);
        }
    }

    private static String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
