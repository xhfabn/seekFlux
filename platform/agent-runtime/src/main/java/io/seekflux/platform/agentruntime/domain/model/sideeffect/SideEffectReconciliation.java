package io.seekflux.platform.agentruntime.domain.model.sideeffect;

import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;

public record SideEffectReconciliation(
        Resolution resolution,
        AgentToolResult result,
        String method,
        String note) {

    public enum Resolution { SUCCEEDED, FAILED, UNKNOWN }

    public SideEffectReconciliation {
        if (resolution == null) {
            throw new IllegalArgumentException("reconciliation resolution is required");
        }
        if (resolution != Resolution.UNKNOWN && result == null) {
            throw new IllegalArgumentException("a resolved reconciliation requires a Tool result");
        }
        if (resolution == Resolution.SUCCEEDED && !result.success()) {
            throw new IllegalArgumentException("successful reconciliation requires a successful result");
        }
        if (resolution == Resolution.FAILED && result.success()) {
            throw new IllegalArgumentException("failed reconciliation requires a failed result");
        }
        method = method == null || method.isBlank() ? "EXTERNAL_STATUS_QUERY" : method;
    }

    public static SideEffectReconciliation succeeded(AgentToolResult result, String method) {
        return new SideEffectReconciliation(Resolution.SUCCEEDED, result, method, null);
    }

    public static SideEffectReconciliation failed(AgentToolResult result, String method) {
        return new SideEffectReconciliation(Resolution.FAILED, result, method, null);
    }

    public static SideEffectReconciliation unknown(String method, String note) {
        return new SideEffectReconciliation(Resolution.UNKNOWN, null, method, note);
    }
}
