package io.seekflux.agent.infrastructure.mcp;

@FunctionalInterface
public interface McpEventRecorder {

    McpEventRecorder NOOP = ignored -> { };

    void record(McpEvent event);
}
