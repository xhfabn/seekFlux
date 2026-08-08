package io.seekflux.platform.agentruntime.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultFeaturePipelineTest {

    @Test
    void sortsExplicitNodesAndKeepsTransientAttributesOutOfRuntimeFeatures() {
        List<Integer> order = new ArrayList<>();
        FeatureNode late = node(350, context -> {
            order.add(350);
            context.runtimeContext(new RuntimeContext(
                    context.request().definition(),
                    context.request().runRequest(),
                    context.request().llmClient(),
                    context.persistentAttributes()));
        });
        FeatureNode early = node(50, context -> {
            order.add(50);
            context.setAttribute("persistent", "yes");
            context.setTransientAttribute("transient", "no");
        });

        FeatureContext result = new DefaultFeaturePipeline(List.of(late, early)).process(request());

        assertEquals(List.of(50, 350), order);
        assertEquals("yes", result.runtimeContext().features().get("persistent"));
        assertFalse(result.runtimeContext().features().containsKey("transient"));
    }

    private static FeatureNode node(int order, java.util.function.Consumer<FeatureContext> action) {
        return new FeatureNode() {
            @Override
            public String name() {
                return "node-" + order;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public void process(FeatureContext context) {
                action.accept(context);
            }
        };
    }

    private static FeatureRequest request() {
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop", "prompt", "decision",
                Set.of("tool"), 2, 1, Duration.ofSeconds(1), true);
        AgentRunRequest run = new AgentRunRequest("request", "session", "turn", "input", Map.of());
        LlmClient llm = new LlmClient() {
            @Override public String version() { return "test"; }
            @Override public AgentDecision chat(io.seekflux.platform.agentruntime.context.AssembledContext context) {
                return new AgentDecision.Complete(Map.of());
            }
        };
        return new FeatureRequest(definition, run, llm);
    }
}
