package io.seekflux.platform.agentruntime.application.spi.capability.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolInvocation;
import java.util.Map;

public interface AgentToolExecutor {

    AgentToolInvocation execute(
            String toolName,
            Map<String, Object> arguments,
            AgentToolContext context);
}
