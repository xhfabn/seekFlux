package io.seekflux.platform.agentruntime.llm;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.context.AssembledContext;

public interface LlmClient {

    String version();

    AgentDecision chat(AssembledContext context);

    default LlmCallResult chatWithUsage(AssembledContext context) {
        return new LlmCallResult(chat(context), LlmUsage.UNMEASURED);
    }
}
