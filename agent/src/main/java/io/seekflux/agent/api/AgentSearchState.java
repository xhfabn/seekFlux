package io.seekflux.agent.api;

public enum AgentSearchState {
    RESULTS_READY,
    QUEUED,
    NEED_CLARIFICATION,
    WAITING,
    FALLBACK_RESULTS,
    CANCELLED,
    FAILED
}
