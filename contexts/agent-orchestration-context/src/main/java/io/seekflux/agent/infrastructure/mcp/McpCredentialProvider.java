package io.seekflux.agent.infrastructure.mcp;

import java.util.Map;

@FunctionalInterface
public interface McpCredentialProvider {

    McpCredentialProvider NONE = ignored -> Map.of();

    /** Resolves a reference to HTTP headers. The resolved values must never enter runtime state. */
    Map<String, String> headers(String credentialRef);
}
