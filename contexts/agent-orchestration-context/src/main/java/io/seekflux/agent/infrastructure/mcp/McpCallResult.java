package io.seekflux.agent.infrastructure.mcp;

import java.util.Map;

public record McpCallResult(boolean error, Map<String, Object> result) {

    public McpCallResult {
        result = result == null ? Map.of() : Map.copyOf(result);
    }
}
