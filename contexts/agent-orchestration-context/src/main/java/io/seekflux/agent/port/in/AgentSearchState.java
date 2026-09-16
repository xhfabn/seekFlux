package io.seekflux.agent.port.in;

public enum AgentSearchState {
    RESULTS_READY,
    QUEUED,
    NEED_CLARIFICATION,
    FALLBACK_RESULTS,
    CANCELLED,
    FAILED
}
