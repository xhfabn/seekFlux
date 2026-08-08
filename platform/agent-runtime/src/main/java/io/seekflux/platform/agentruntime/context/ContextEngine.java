package io.seekflux.platform.agentruntime.context;

import io.seekflux.platform.agentruntime.AgentDecisionContext;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.session.AgentSession;

public interface ContextEngine {

    AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext);
}
