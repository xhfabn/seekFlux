package io.seekflux.platform.agentruntime.execution;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean steer;

    public void cancel(boolean steer) {
        this.steer = this.steer || steer;
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isSteer() {
        return steer;
    }
}
