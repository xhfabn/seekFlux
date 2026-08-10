package io.seekflux.platform.agentruntime.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final String sessionId;
    private final Instant taskStartedAt;
    private final CancellationSignalStore remoteSignals;
    private final long remotePollIntervalNanos;
    private volatile long nextRemotePollNanos;
    private volatile boolean steer;

    public CancellationToken() {
        this(null, null, CancellationSignalStore.NOOP, Duration.ZERO);
    }

    public CancellationToken(
            String sessionId,
            Instant taskStartedAt,
            CancellationSignalStore remoteSignals,
            Duration remotePollInterval) {
        this.sessionId = sessionId;
        this.taskStartedAt = taskStartedAt;
        this.remoteSignals = Objects.requireNonNull(remoteSignals, "remote signal store must not be null");
        this.remotePollIntervalNanos = Math.max(0, remotePollInterval.toNanos());
    }

    public void cancel(boolean steer) {
        this.steer = this.steer || steer;
        cancelled.set(true);
    }

    public boolean isCancelled() {
        pollRemoteIfDue();
        return cancelled.get();
    }

    public boolean isSteer() {
        pollRemoteIfDue();
        return steer;
    }

    private void pollRemoteIfDue() {
        if (cancelled.get() || sessionId == null || taskStartedAt == null) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextRemotePollNanos) {
            return;
        }
        nextRemotePollNanos = now + remotePollIntervalNanos;
        CancellationSignalStore.CancelSignal signal = remoteSignals.poll(sessionId, taskStartedAt);
        if (signal.cancelled()) {
            cancel(signal.steer());
        }
    }
}
