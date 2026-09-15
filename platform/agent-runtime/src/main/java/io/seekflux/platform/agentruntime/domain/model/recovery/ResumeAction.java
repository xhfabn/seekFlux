package io.seekflux.platform.agentruntime.domain.model.recovery;

public enum ResumeAction {
    START_NEW,
    RESUME_PRE_TURN,
    RESUME_POST_TURN,
    RESUME_PENDING_TOOLS,
    COMMIT_TERMINAL,
    FAIL_UNSAFE_PENDING_TOOL
}
