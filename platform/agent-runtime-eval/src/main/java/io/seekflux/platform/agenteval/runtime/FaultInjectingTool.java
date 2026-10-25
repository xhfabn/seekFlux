package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.*;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.*;
import java.util.Objects;
import static io.seekflux.platform.agenteval.Evaluation.Phase.*;

/** Decorates the actual tool body, preserving schema, authorization metadata and Runtime checks. */
public final class FaultInjectingTool implements AgentTool {
    private final AgentTool delegate;
    private final FaultController faults;
    public FaultInjectingTool(AgentTool delegate, FaultController faults) {
        this.delegate = Objects.requireNonNull(delegate); this.faults = Objects.requireNonNull(faults);
    }
    public String name() { return delegate.name(); }
    public String description() { return delegate.description(); }
    public AgentToolSchema schema() { return delegate.schema(); }
    public Effect effect() { return delegate.effect(); }
    public String source() { return delegate.source(); }
    public boolean approvalRequired() { return delegate.approvalRequired(); }
    public String approvalReason() { return delegate.approvalReason(); }
    public AgentToolResult execute(AgentToolContext context) {
        faults.at(BEFORE_TOOL, name(), context.cancellationToken());
        AgentToolResult result = delegate.execute(context);
        faults.at(AFTER_TOOL, name(), context.cancellationToken());
        return result;
    }
}

