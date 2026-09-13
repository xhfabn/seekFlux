package io.seekflux.platform.agentruntime.domain.exception;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;

public final class AgentCancellationException extends RuntimeException {

    private final CancellationCause cancellationCause;

    public AgentCancellationException(CancellationCause cancellationCause) {
        super(cancellationCause.name());
        this.cancellationCause = cancellationCause;
    }

    public CancellationCause cancellationCause() {
        return cancellationCause;
    }
}
