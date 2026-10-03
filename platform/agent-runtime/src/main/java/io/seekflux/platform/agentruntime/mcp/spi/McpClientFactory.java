package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;

@FunctionalInterface
public interface McpClientFactory {

    McpClient create(McpServerConfig config);
}
