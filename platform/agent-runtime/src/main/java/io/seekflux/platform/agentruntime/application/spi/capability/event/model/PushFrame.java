package io.seekflux.platform.agentruntime.application.spi.capability.event.model;

import java.time.Instant;

public record PushFrame(
        String sessionId,
        String requestId,
        long sequence,
        String sourceId,
        PushEvent event,
        Instant publishedAt) {

    public PushFrame {
        if (sessionId == null || sessionId.isBlank() || sequence < 0
                || sourceId == null || sourceId.isBlank()
                || event == null || publishedAt == null) {
            throw new IllegalArgumentException("push frame identity, sequence and event are required");
        }
        requestId = requestId == null ? "" : requestId;
    }
}
