package io.seekflux.platform.agentruntime.infrastructure.tool;

import io.seekflux.platform.agentruntime.application.spi.capability.tool.AgentToolExecutor;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolInvocation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import java.util.Map;

public final class DefaultAgentToolExecutor implements AgentToolExecutor {

    private final AgentToolRegistry registry;

    public DefaultAgentToolExecutor(AgentToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AgentToolInvocation execute(
            String toolName,
            Map<String, Object> arguments,
            AgentToolContext context) {
        context.cancellationToken().throwIfCancelled();
        AgentTool tool = registry.require(toolName);
        tool.schema().validate(arguments);
        try {
            AgentToolResult result = tool.execute(context);
            context.cancellationToken().throwIfCancelled();
            return new AgentToolInvocation(
                    tool.name(),
                    tool.schema().version(),
                    result == null ? AgentToolResult.failure("TOOL_RETURNED_NULL") : result);
        } catch (AgentCancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException error) {
            if (tool.effect() == AgentTool.Effect.MUTATING) {
                throw error;
            }
            return new AgentToolInvocation(
                    tool.name(),
                    tool.schema().version(),
                    AgentToolResult.failure("TOOL_EXECUTION_FAILED"));
        }
    }
}
