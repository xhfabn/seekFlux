package io.seekflux.platform.agentruntime.application.spi.business.planner;

import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;

@FunctionalInterface
public interface AgentPlanner {

    AgentDecision decide(AgentDecisionContext context);
}
