package io.seekflux.platform.agentruntime.domain.model.session;

public record QueueCommitResult(Status status, int queueDepth) {

    public enum Status {
        COMMITTED,
        DUPLICATE_PENDING,
        DUPLICATE_CONSUMED,
        FULL
    }

    public QueueCommitResult {
        if (status == null) {
            throw new IllegalArgumentException("queue commit status must not be null");
        }
        if (queueDepth < 0) {
            throw new IllegalArgumentException("queue depth must not be negative");
        }
    }

    public static QueueCommitResult committed(int queueDepth) {
        return new QueueCommitResult(Status.COMMITTED, queueDepth);
    }

    public static QueueCommitResult duplicatePending(int queueDepth) {
        return new QueueCommitResult(Status.DUPLICATE_PENDING, queueDepth);
    }

    public static QueueCommitResult duplicateConsumed(int queueDepth) {
        return new QueueCommitResult(Status.DUPLICATE_CONSUMED, queueDepth);
    }

    public static QueueCommitResult full(int queueDepth) {
        return new QueueCommitResult(Status.FULL, queueDepth);
    }
}
