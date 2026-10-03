package io.seekflux.platform.agentruntime.mcp.model;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record McpServerConfig(
        int schemaVersion,
        String serverId,
        String configVersion,
        Transport transport,
        URI endpoint,
        String credentialRef,
        String protocolVersion,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxConcurrentCalls,
        int circuitFailureThreshold,
        Duration circuitOpenDuration,
        int maxResponseBytes,
        Map<String, McpToolPolicy> toolPolicies) {

    public static final String SUPPORTED_PROTOCOL_VERSION = "2025-11-25";

    public McpServerConfig {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("MCP server schema version must be positive");
        }
        serverId = identifier(serverId, "MCP server id");
        configVersion = text(configVersion, "MCP config version", 128);
        transport = transport == null ? Transport.STREAMABLE_HTTP : transport;
        if (endpoint == null || !endpoint.isAbsolute() || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must be absolute, without credentials or fragment");
        }
        if (transport == Transport.STREAMABLE_HTTP
                && (!("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getHost() == null)) {
            throw new IllegalArgumentException("MCP endpoint must use HTTP or HTTPS");
        }
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        if (credentialRef.length() > 256) {
            throw new IllegalArgumentException("MCP credential reference is too long");
        }
        protocolVersion = text(protocolVersion, "MCP protocol version", 32);
        if (!protocolVersion.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid MCP protocol version");
        }
        connectTimeout = positive(connectTimeout, "MCP connect timeout");
        requestTimeout = positive(requestTimeout, "MCP request timeout");
        if (maxConcurrentCalls < 1 || maxConcurrentCalls > 128) {
            throw new IllegalArgumentException("MCP max concurrent calls must be between 1 and 128");
        }
        if (circuitFailureThreshold < 1 || circuitFailureThreshold > 100) {
            throw new IllegalArgumentException("MCP circuit threshold must be between 1 and 100");
        }
        circuitOpenDuration = positive(circuitOpenDuration, "MCP circuit open duration");
        if (maxResponseBytes < 1_024 || maxResponseBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "MCP max response bytes must be between 1024 and 16777216");
        }
        toolPolicies = toolPolicies == null ? Map.of() : Map.copyOf(toolPolicies);
        if (toolPolicies.size() > 128) {
            throw new IllegalArgumentException("MCP Tool policy count must not exceed 128");
        }
        toolPolicies.forEach((name, policy) -> {
            if (!name.equals(policy.remoteToolName())) {
                throw new IllegalArgumentException("MCP Tool policy key must match remote name");
            }
        });
    }

    public String source() {
        return "mcp:" + serverId;
    }

    public enum Transport {
        STREAMABLE_HTTP,
        CUSTOM
    }

    /** Config-only default setup. Tool effect/approval policies remain explicit local decisions. */
    public static McpServerConfig streamableHttp(
            String serverId, URI endpoint, Map<String, McpToolPolicy> toolPolicies) {
        return new McpServerConfig(1, serverId, "mcp-config-v1", Transport.STREAMABLE_HTTP,
                endpoint, "", SUPPORTED_PROTOCOL_VERSION, Duration.ofSeconds(2),
                Duration.ofSeconds(5), 8, 3, Duration.ofSeconds(30), 1024 * 1024, toolPolicies);
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    static String identifier(String value, String name) {
        String normalized = text(value, name, 64);
        if (!normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    static String text(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
