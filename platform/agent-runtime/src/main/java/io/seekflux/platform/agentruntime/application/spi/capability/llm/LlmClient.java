package io.seekflux.platform.agentruntime.application.spi.capability.llm;

import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.LlmCallResult;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;

public interface LlmClient {

    String version();

    AgentDecision chat(AssembledContext context);

    default LlmCallResult chatWithUsage(AssembledContext context) {
        return new LlmCallResult(chat(context), LlmUsage.UNMEASURED);
    }
}
