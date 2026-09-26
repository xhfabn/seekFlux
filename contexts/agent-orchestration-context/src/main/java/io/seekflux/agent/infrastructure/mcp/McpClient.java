package io.seekflux.agent.infrastructure.mcp;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface McpClient extends AutoCloseable {

    List<McpRemoteTool> initializeAndList(CancellationToken cancellationToken);

    McpCallResult callTool(
            String remoteToolName,
            Map<String, Object> arguments,
            Duration timeout,
            CancellationToken cancellationToken);

    @Override
    void close();
}
