package io.seekflux.platform.agentruntime.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentDecisionContext;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import io.seekflux.platform.agentruntime.context.ContextMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ShadowingLlmClientTest {

    @Test
    void shadowNeverChangesPrimaryResultAndCanBeDisabledImmediately() throws Exception {
        ShadowControl control = new ShadowControl(true, 1.0);
        List<ShadowEvaluation> evaluations = new ArrayList<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            LlmClient primary = client("primary-v1", new AgentDecision.Complete(Map.of("source", "primary")));
            LlmClient shadow = client("shadow-v2", new AgentDecision.Fallback("candidate"));
            ShadowingLlmClient client = new ShadowingLlmClient(
                    primary, shadow, "shadow-policy-v2", control, executor, evaluations::add,
                    Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

            AgentDecision first = client.chat(context("shadow-request-1"));
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(new AgentDecision.Complete(Map.of("source", "primary")), first);
            assertEquals(1, evaluations.size());
            assertFalse(evaluations.getFirst().agreed());

            control.update(false, 0);
            assertFalse(control.shouldSample("shadow-request-2"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sharedSettingsDisableShadowAcrossInstances() {
        AtomicReference<ShadowControl.Settings> shared = new AtomicReference<>();
        ShadowSettingsStore store = new ShadowSettingsStore() {
            @Override
            public Optional<ShadowControl.Settings> load() {
                return Optional.ofNullable(shared.get());
            }

            @Override
            public void save(ShadowControl.Settings settings) {
                shared.set(settings);
            }
        };
        ShadowControl first = new ShadowControl(true, 1.0, store);
        ShadowControl second = new ShadowControl(true, 1.0, store);

        assertTrue(second.shouldSample("cross-instance-request"));
        first.update(false, 0);

        assertFalse(second.shouldSample("cross-instance-request"));
        assertEquals(new ShadowControl.Settings(false, 0), second.current());
    }

    private static LlmClient client(String version, AgentDecision decision) {
        return new LlmClient() {
            @Override public String version() { return version; }
            @Override public AgentDecision chat(AssembledContext context) { return decision; }
        };
    }

    private static AssembledContext context(String requestId) {
        AgentRunRequest request = new AgentRunRequest(
                requestId, "session", "turn", "query", Map.of());
        return new AssembledContext(
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()),
                List.of(new ContextMessage("system", "prompt")),
                "spec",
                1);
    }
}
