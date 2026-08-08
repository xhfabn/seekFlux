package io.seekflux.platform.agentruntime;

import java.util.Map;

public record AgentToolResult(
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String linkedTraceId) {

    public AgentToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        if (success && errorCode != null) {
            throw new IllegalArgumentException("a successful tool result cannot contain an error code");
        }
        if (!success && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("a failed tool result must contain an error code");
        }
    }

    public static AgentToolResult success(Map<String, Object> output, String linkedTraceId) {
        return new AgentToolResult(true, output, null, linkedTraceId);
    }

    public static AgentToolResult failure(String errorCode) {
        return new AgentToolResult(false, Map.of(), errorCode, null);
    }
}
