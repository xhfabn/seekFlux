package io.seekflux.platform.agentruntime;

@FunctionalInterface
public interface AgentPlanner {

    AgentDecision decide(AgentDecisionContext context);
}
