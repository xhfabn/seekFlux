package io.seekflux.platform.agentruntime.domain.model.execution;

import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CancellationToken {

    private final AtomicReference<CancellationCause> localCause = new AtomicReference<>();
    private final CancellationToken parent;
    private final String sessionId;
    private final Instant taskStartedAt;
    private final CancellationSignalStore remoteSignals;
    private final long remotePollIntervalNanos;
    private volatile long nextRemotePollNanos;

    public CancellationToken() {
        this(null, null, null, CancellationSignalStore.NOOP, Duration.ZERO);
    }

    public CancellationToken(
            String sessionId,
            Instant taskStartedAt,
            CancellationSignalStore remoteSignals,
            Duration remotePollInterval) {
        this(null, sessionId, taskStartedAt, remoteSignals, remotePollInterval);
    }

    private CancellationToken(
            CancellationToken parent,
            String sessionId,
            Instant taskStartedAt,
            CancellationSignalStore remoteSignals,
            Duration remotePollInterval) {
        this.parent = parent;
        this.sessionId = sessionId;
        this.taskStartedAt = taskStartedAt;
        this.remoteSignals = Objects.requireNonNull(remoteSignals, "remote signal store must not be null");
        Objects.requireNonNull(remotePollInterval, "remote poll interval must not be null");
        this.remotePollIntervalNanos = Math.max(0, remotePollInterval.toNanos());
    }

    public CancellationToken child() {
        return new CancellationToken(this, null, null, CancellationSignalStore.NOOP, Duration.ZERO);
    }

    public void cancel(CancellationCause cause) {
        localCause.compareAndSet(null, Objects.requireNonNull(cause, "cancellation cause must not be null"));
    }

    public void cancel(boolean steer) {
        cancel(steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL);
    }

    public boolean isCancelled() {
        return cause() != null;
    }

    public boolean isSteer() {
        CancellationCause cause = cause();
        return cause != null && cause.isSteer();
    }

    public CancellationCause cause() {
        CancellationCause local = localCause.get();
        if (local != null) {
            return local;
        }
        if (parent != null) {
            return parent.cause();
        }
        pollRemoteIfDue();
        return localCause.get();
    }

    public void throwIfCancelled() {
        CancellationCause cause = cause();
        if (cause != null) {
            throw new AgentCancellationException(cause);
        }
    }

    private void pollRemoteIfDue() {
        if (localCause.get() != null || sessionId == null || taskStartedAt == null) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextRemotePollNanos) {
            return;
        }
        nextRemotePollNanos = now + remotePollIntervalNanos;
        CancellationSignalStore.CancelSignal signal = remoteSignals.poll(sessionId, taskStartedAt);
        if (signal.cancelled()) {
            cancel(signal.cause());
        }
    }
}
