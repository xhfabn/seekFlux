package io.seekflux.platform.agentruntime.domain.model.tool;

import java.util.Map;

public record AgentToolResult(
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String linkedTraceId,
        Map<String, Object> externalReceipt) {

    public AgentToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        externalReceipt = externalReceipt == null ? Map.of() : Map.copyOf(externalReceipt);
        if (success && errorCode != null) {
            throw new IllegalArgumentException("a successful tool result cannot contain an error code");
        }
        if (!success && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("a failed tool result must contain an error code");
        }
    }

    public AgentToolResult(
            boolean success,
            Map<String, Object> output,
            String errorCode,
            String linkedTraceId) {
        this(success, output, errorCode, linkedTraceId, Map.of());
    }

    public static AgentToolResult success(Map<String, Object> output, String linkedTraceId) {
        return new AgentToolResult(true, output, null, linkedTraceId, Map.of());
    }

    public static AgentToolResult success(
            Map<String, Object> output,
            String linkedTraceId,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(true, output, null, linkedTraceId, externalReceipt);
    }

    public static AgentToolResult failure(String errorCode) {
        return new AgentToolResult(false, Map.of(), errorCode, null, Map.of());
    }

    public static AgentToolResult failure(
            String errorCode,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(false, Map.of(), errorCode, null, externalReceipt);
    }
}
