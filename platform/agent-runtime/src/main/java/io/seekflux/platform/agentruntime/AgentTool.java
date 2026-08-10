package io.seekflux.platform.agentruntime;

public interface AgentTool {

    enum Effect { READ_ONLY, IDEMPOTENT, MUTATING }

    String name();

    AgentToolSchema schema();

    default Effect effect() {
        return Effect.MUTATING;
    }

    AgentToolResult execute(AgentToolContext context);
}
