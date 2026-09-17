package io.seekflux.platform.agentruntime.domain.model.tool;

import java.util.Map;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;

public record AgentToolResult(
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String linkedTraceId,
        Map<String, Object> externalReceipt,
        WaitRequest waitRequest) {

    public AgentToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        externalReceipt = externalReceipt == null ? Map.of() : Map.copyOf(externalReceipt);
        if (waitRequest != null && (success || errorCode != null)) {
            throw new IllegalArgumentException(
                    "a waiting Tool result is neither successful nor failed");
        }
        if (success && errorCode != null) {
            throw new IllegalArgumentException("a successful tool result cannot contain an error code");
        }
        if (!success && waitRequest == null && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("a failed tool result must contain an error code");
        }
    }

    public AgentToolResult(
            boolean success,
            Map<String, Object> output,
            String errorCode,
            String linkedTraceId) {
        this(success, output, errorCode, linkedTraceId, Map.of(), null);
    }

    public AgentToolResult(
            boolean success,
            Map<String, Object> output,
            String errorCode,
            String linkedTraceId,
            Map<String, Object> externalReceipt) {
        this(success, output, errorCode, linkedTraceId, externalReceipt, null);
    }

    public static AgentToolResult success(Map<String, Object> output, String linkedTraceId) {
        return new AgentToolResult(true, output, null, linkedTraceId, Map.of(), null);
    }

    public static AgentToolResult success(
            Map<String, Object> output,
            String linkedTraceId,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(true, output, null, linkedTraceId, externalReceipt, null);
    }

    public static AgentToolResult failure(String errorCode) {
        return new AgentToolResult(false, Map.of(), errorCode, null, Map.of(), null);
    }

    public static AgentToolResult failure(
            String errorCode,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(false, Map.of(), errorCode, null, externalReceipt, null);
    }

    public static AgentToolResult waiting(WaitRequest request) {
        return new AgentToolResult(false, Map.of(), null, null, Map.of(), request);
    }

    public boolean waiting() {
        return waitRequest != null;
    }
}
