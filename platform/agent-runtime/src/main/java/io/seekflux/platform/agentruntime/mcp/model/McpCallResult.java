package io.seekflux.platform.agentruntime.mcp.model;

import java.util.Map;

public record McpCallResult(boolean error, Map<String, Object> result) {

    public McpCallResult {
        result = result == null ? Map.of() : Map.copyOf(result);
    }
}
