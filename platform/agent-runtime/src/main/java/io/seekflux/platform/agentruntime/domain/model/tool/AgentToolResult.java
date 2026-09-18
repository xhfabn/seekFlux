package io.seekflux.platform.agentruntime.domain.model.tool;

import java.util.Map;
import java.util.Set;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;
import io.seekflux.platform.agentruntime.domain.model.capability.ToolGroupSwitch;

public record AgentToolResult(
        boolean success,
        Map<String, Object> output,
        String errorCode,
        String linkedTraceId,
        Map<String, Object> externalReceipt,
        WaitRequest waitRequest,
        ToolGroupSwitch toolGroupSwitch) {

    public AgentToolResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        externalReceipt = externalReceipt == null ? Map.of() : Map.copyOf(externalReceipt);
        if (waitRequest != null && (success || errorCode != null)) {
            throw new IllegalArgumentException(
                    "a waiting Tool result is neither successful nor failed");
        }
        if (toolGroupSwitch != null && !success) {
            throw new IllegalArgumentException("only a successful Tool result can switch ToolGroups");
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
        this(success, output, errorCode, linkedTraceId, Map.of(), null, null);
    }

    public AgentToolResult(
            boolean success,
            Map<String, Object> output,
            String errorCode,
            String linkedTraceId,
            Map<String, Object> externalReceipt) {
        this(success, output, errorCode, linkedTraceId, externalReceipt, null, null);
    }

    public static AgentToolResult success(Map<String, Object> output, String linkedTraceId) {
        return new AgentToolResult(true, output, null, linkedTraceId, Map.of(), null, null);
    }

    public static AgentToolResult success(
            Map<String, Object> output,
            String linkedTraceId,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(true, output, null, linkedTraceId, externalReceipt, null, null);
    }

    public static AgentToolResult failure(String errorCode) {
        return new AgentToolResult(false, Map.of(), errorCode, null, Map.of(), null, null);
    }

    public static AgentToolResult failure(
            String errorCode,
            Map<String, Object> externalReceipt) {
        return new AgentToolResult(false, Map.of(), errorCode, null, externalReceipt, null, null);
    }

    public static AgentToolResult waiting(WaitRequest request) {
        return new AgentToolResult(false, Map.of(), null, null, Map.of(), request, null);
    }

    public static AgentToolResult switchToolGroups(
            Map<String, Object> output, String linkedTraceId, Set<String> activeGroups) {
        return new AgentToolResult(
                true, output, null, linkedTraceId, Map.of(), null,
                new ToolGroupSwitch(activeGroups));
    }

    public boolean waiting() {
        return waitRequest != null;
    }
}
