package io.seekflux.platform.agentruntime.domain.model.session;

import java.time.Instant;
import java.util.List;

public record QueuedMessageBatch(List<WorkspaceEvent.QueuedUserMessage> messages) {

    public QueuedMessageBatch {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("a queued message batch must not be empty");
        }
    }

    public WorkspaceEvent.QueuedUserMessage last() {
        return messages.getLast();
    }

    public Instant signalCutoff() {
        return messages.stream()
                .map(WorkspaceEvent.QueuedUserMessage::eventTime)
                .max(Instant::compareTo)
                .orElseThrow();
    }
}
