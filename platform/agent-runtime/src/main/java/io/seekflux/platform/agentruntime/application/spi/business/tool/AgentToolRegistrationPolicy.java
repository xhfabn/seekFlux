package io.seekflux.platform.agentruntime.application.spi.business.tool;

@FunctionalInterface
public interface AgentToolRegistrationPolicy {

    AgentToolRegistrationPolicy SAFE_ONLY = tool -> tool.effect() != AgentTool.Effect.MUTATING;
    AgentToolRegistrationPolicy ALLOW_MUTATING = tool -> true;

    boolean allow(AgentTool tool);
}
