package io.seekflux.platform.agentruntime.application.spi.business.tool.model;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import java.time.Duration;
import java.util.Map;

public record AgentToolContext(
        String agentRunId,
        String toolCallId,
        String idempotencyKey,
        AgentRunRequest request,
        Map<String, Object> arguments,
        Duration remaining,
        CancellationToken cancellationToken) {

    public AgentToolContext(
            String agentRunId,
            String toolCallId,
            AgentRunRequest request,
            Map<String, Object> arguments,
            Duration remaining) {
        this(agentRunId, toolCallId, request, arguments, remaining, new CancellationToken());
    }

    public AgentToolContext(
            String agentRunId,
            String toolCallId,
            AgentRunRequest request,
            Map<String, Object> arguments,
            Duration remaining,
            CancellationToken cancellationToken) {
        this(agentRunId, toolCallId, "tool-call:" + toolCallId, request,
                arguments, remaining, cancellationToken);
    }

    public AgentToolContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        idempotencyKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "tool-call:" + toolCallId : idempotencyKey;
        cancellationToken = cancellationToken == null ? new CancellationToken() : cancellationToken;
    }
}
