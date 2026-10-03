package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.mcp.model.McpEvent;

@FunctionalInterface
public interface McpEventRecorder {

    McpEventRecorder NOOP = ignored -> { };

    void record(McpEvent event);
}
