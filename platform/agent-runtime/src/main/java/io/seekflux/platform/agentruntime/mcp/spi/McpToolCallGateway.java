package io.seekflux.platform.agentruntime.mcp.spi;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import java.time.Duration;
import java.util.Map;

/** Connection boundary used by Tool proxies; hosts normally customize McpClientFactory instead. */
public interface McpToolCallGateway {

    McpCallResult call(
            String serverId,
            String localToolName,
            String expectedSchemaVersion,
            String remoteToolName,
            Map<String, Object> arguments,
            Duration remaining,
            CancellationToken cancellationToken);

    void recordPolicyRejection(String serverId, String localToolName, String reason);
}
