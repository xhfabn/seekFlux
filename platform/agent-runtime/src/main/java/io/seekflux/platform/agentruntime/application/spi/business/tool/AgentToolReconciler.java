package io.seekflux.platform.agentruntime.application.spi.business.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;

/** Queries external facts for an ambiguous mutating call. It must never repeat the mutation. */
public interface AgentToolReconciler {

    SideEffectReconciliation reconcile(
            SideEffectLedgerEntry ledgerEntry,
            AgentToolContext context);
}
