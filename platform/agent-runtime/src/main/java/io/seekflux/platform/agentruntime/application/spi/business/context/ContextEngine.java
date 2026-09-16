package io.seekflux.platform.agentruntime.application.spi.business.context;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;

public interface ContextEngine {

    AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext);

    default AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext,
            ContextAssemblyMode mode) {
        return assemble(session, runtimeContext, decisionContext);
    }

    default int overflowRetryLimit() {
        return 0;
    }
}
