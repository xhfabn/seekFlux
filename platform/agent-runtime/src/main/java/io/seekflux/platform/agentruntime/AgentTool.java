package io.seekflux.platform.agentruntime;

public interface AgentTool {

    String name();

    AgentToolSchema schema();

    AgentToolResult execute(AgentToolContext context);
}
