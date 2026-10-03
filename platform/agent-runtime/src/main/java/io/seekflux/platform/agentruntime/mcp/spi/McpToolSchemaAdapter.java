package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.mcp.model.McpTranslatedTool;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;

/** Maps a remote schema to the Runtime's supported local schema; implementations must fail closed. */
@FunctionalInterface
public interface McpToolSchemaAdapter {

    McpTranslatedTool translate(
            McpServerConfig server,
            McpRemoteTool remote,
            McpToolPolicy policy,
            String reconciliationSchemaHash);
}
