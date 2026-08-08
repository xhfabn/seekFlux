package io.seekflux.platform.agentruntime.loop;

import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.execution.CancellationToken;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.session.AgentSession;

public interface AgentLoop {

    String loopType();

    AgentRunResult run(
            AgentSession session,
            RuntimeContext context,
            PushEventPublisher publisher,
            CancellationToken cancellationToken);
}
