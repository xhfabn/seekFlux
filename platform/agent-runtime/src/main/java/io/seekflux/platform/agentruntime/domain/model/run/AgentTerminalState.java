package io.seekflux.platform.agentruntime.domain.model.run;

public enum AgentTerminalState {
    RESULTS_READY,
    NEED_CLARIFICATION,
    FALLBACK_REQUIRED,
    CANCELLED,
    FAILED
}
