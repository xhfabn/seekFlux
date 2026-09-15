package io.seekflux.platform.agentruntime.domain.model.recovery;

public enum ResumeSource {
    USER_MESSAGE,
    CRASH_RECOVERY,
    HITL_CALLBACK,
    ASYNC_CALLBACK,
    WAITPOINT_CALLBACK,
    CHILD_AGENT_CALLBACK
}
