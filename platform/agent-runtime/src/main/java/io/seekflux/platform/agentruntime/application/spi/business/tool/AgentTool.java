package io.seekflux.platform.agentruntime.application.spi.business.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;

public interface AgentTool {

    enum Effect { READ_ONLY, IDEMPOTENT, MUTATING }

    String name();

    AgentToolSchema schema();

    default Effect effect() {
        return Effect.MUTATING;
    }

    AgentToolResult execute(AgentToolContext context);
}
