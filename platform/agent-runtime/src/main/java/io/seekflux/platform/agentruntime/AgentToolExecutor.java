package io.seekflux.platform.agentruntime;

import java.util.Map;

public interface AgentToolExecutor {

    AgentToolInvocation execute(
            String toolName,
            Map<String, Object> arguments,
            AgentToolContext context);
}
