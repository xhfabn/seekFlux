package io.seekflux.platform.agentruntime.domain.service.loop;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;

public interface AgentLoop {

    String loopType();

    AgentRunResult run(
            AgentSession session,
            RuntimeContext context,
            PushEventPublisher publisher,
            CancellationToken cancellationToken);
}
