package io.seekflux.platform.agentruntime.application.spi.capability.llm;

import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.LlmCallResult;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatChunk;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import java.util.function.Consumer;

public interface LlmClient {

    String version();

    AgentDecision chat(AssembledContext context);

    default AgentDecision chat(AssembledContext context, CancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();
        AgentDecision decision = chat(context);
        cancellationToken.throwIfCancelled();
        return decision;
    }

    default LlmCallResult chatWithUsage(AssembledContext context) {
        return new LlmCallResult(chat(context), LlmUsage.UNMEASURED);
    }

    default LlmCallResult chatWithUsage(
            AssembledContext context,
            CancellationToken cancellationToken) {
        cancellationToken.throwIfCancelled();
        LlmCallResult result = chatWithUsage(context);
        cancellationToken.throwIfCancelled();
        return result;
    }

    default LlmCallResult streamWithUsage(
            AssembledContext context,
            CancellationToken cancellationToken,
            Consumer<ChatChunk> chunks) {
        cancellationToken.throwIfCancelled();
        LlmCallResult result = chatWithUsage(context, cancellationToken);
        chunks.accept(new ChatChunk(
                0,
                result.assistantContent().content(),
                result.assistantContent().reasoning(),
                null,
                result.usage(),
                "stop",
                true));
        cancellationToken.throwIfCancelled();
        return result;
    }
}
