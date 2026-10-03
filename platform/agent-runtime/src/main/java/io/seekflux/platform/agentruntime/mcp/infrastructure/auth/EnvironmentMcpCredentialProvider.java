package io.seekflux.platform.agentruntime.mcp.infrastructure.auth;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Resolves env:NAME as a Bearer token at call time, without persisting credentials. */
public final class EnvironmentMcpCredentialProvider implements McpCredentialProvider {

    private final Function<String, String> environment;

    public EnvironmentMcpCredentialProvider() {
        this(System::getenv);
    }

    public EnvironmentMcpCredentialProvider(Function<String, String> environment) {
        this.environment = Objects.requireNonNull(environment);
    }

    @Override
    public Map<String, String> headers(String reference) {
        if (reference == null || reference.isBlank()) {
            return Map.of();
        }
        if (!reference.startsWith("env:")
                || !reference.substring(4).matches("[A-Z][A-Z0-9_]{0,127}")) {
            throw new McpException("MCP_CREDENTIAL_REFERENCE_INVALID", false);
        }
        String secret = environment.apply(reference.substring(4));
        if (secret == null || secret.isBlank()) {
            throw new McpException("MCP_CREDENTIAL_UNAVAILABLE", false);
        }
        return Map.of("Authorization", "Bearer " + secret);
    }
}
