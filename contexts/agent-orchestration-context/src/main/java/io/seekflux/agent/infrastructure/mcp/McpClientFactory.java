package io.seekflux.agent.infrastructure.mcp;

@FunctionalInterface
public interface McpClientFactory {

    McpClient create(McpServerConfig config);
}
