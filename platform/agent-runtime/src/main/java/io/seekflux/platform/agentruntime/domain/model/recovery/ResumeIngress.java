package io.seekflux.platform.agentruntime.domain.model.recovery;

public record ResumeIngress(
        int schemaVersion,
        String sessionId,
        String requestId,
        String turnId,
        ResumeSource source) {

    public ResumeIngress {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("resume ingress schema version must be positive");
        }
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        if (source == null) {
            throw new IllegalArgumentException("resume source must not be null");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
