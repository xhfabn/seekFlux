package io.seekflux.agent.port.in;

public enum AgentSearchState {
    RESULTS_READY,
    QUEUED,
    NEED_CLARIFICATION,
    WAITING,
    FALLBACK_RESULTS,
    CANCELLED,
    FAILED
}
