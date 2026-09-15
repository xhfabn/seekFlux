package io.seekflux.platform.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRecoveryCodecTest {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentRecoveryCodec codec = new AgentRecoveryCodec(objectMapper);

    @Test
    void roundTripsTerminalCheckpointAcrossJsonBoundary() throws Exception {
        AgentMessage.Assistant assistant = assistant();
        AgentToolObservation observation = observation();
        AgentMessage.ToolResult toolResult = new AgentMessage.ToolResult(
                1, "result-message", "request", "turn", "attempt", 1,
                "call", "search", "search-v1", AgentMessage.ToolResultStatus.SUCCEEDED,
                "search:{answer=ok}", Map.of("answer", "ok"), Map.of("answer", "ok"),
                Map.of("answer", "ok"), List.of(), null, "trace", false, 7);
        AgentRunTrace trace = trace(List.of(new AgentRunTrace.StepTrace(
                1, "CALL_TOOL", "SUCCEEDED", "call", "search", "trace", 7, null)));
        AgentRunResult terminal = new AgentRunResult(
                AgentTerminalState.RESULTS_READY,
                Map.of("answer", "done"),
                null,
                null,
                null,
                false,
                List.of(assistant, toolResult),
                trace);
        RuntimeCheckpoint source = new RuntimeCheckpoint(
                1,
                "00000000-0000-0000-0000-000000000010",
                CheckpointBoundary.COMPLETED,
                "session",
                "request",
                "00000000-0000-0000-0000-000000000001",
                "turn",
                9,
                2,
                trace.definition(),
                2,
                1,
                900,
                Map.of("goal", "camp"),
                List.of(observation),
                List.of(assistant, toolResult),
                Set.of("search:{query=camp}"),
                new LlmUsage(10, 5, 15, 3, true),
                trace.steps(),
                terminal,
                NOW);

        Map<String, Object> payload = json(codec.encodeCheckpoint(source));
        payload.put("futureField", Map.of("schemaVersion", 2));
        RuntimeCheckpoint restored = codec.decodeCheckpoint(
                source.schemaVersion(), source.checkpointId(), source.boundary().name(),
                source.sessionId(), source.requestId(), source.turnId(), source.attemptId(),
                source.fencingToken(), source.messageCutoff(), source.nextStep(),
                source.toolCallCount(), source.remainingBudgetMillis(), source.createdAt(), payload);

        assertEquals(source, restored);
        assertEquals("done", restored.terminalResult().output().get("answer"));
    }

    @Test
    void roundTripsUnknownSafeToolJournalForRecovery() throws Exception {
        ToolCallJournalEntry source = new ToolCallJournalEntry(
                1, "session", "request", "turn",
                "00000000-0000-0000-0000-000000000001",
                1, 0, "call", "search", "search-v1",
                AgentTool.Effect.READ_ONLY, ToolJournalStatus.UNKNOWN,
                Map.of("query", "camp"), "digest", false, assistant(), null, NOW);

        ToolCallJournalEntry restored = codec.decodeJournal(
                source.schemaVersion(), source.sessionId(), source.requestId(), source.turnId(),
                source.attemptId(), source.step(), source.callIndex(), source.toolCallId(),
                source.toolName(), source.toolSchemaVersion(), source.effect().name(),
                source.status().name(), source.argumentsDigest(), source.updatedAt(),
                json(codec.encodeJournal(source)));

        assertEquals(source, restored);
        assertEquals(true, restored.safeToRetry());
    }

    private static AgentMessage.Assistant assistant() {
        return new AgentMessage.Assistant(
                1, "assistant", "request", "turn", "attempt", 1,
                "search", null, false,
                List.of(new AgentMessage.ToolCall(
                        "call", "search", 0, Map.of("query", "camp"))));
    }

    private static AgentToolObservation observation() {
        return new AgentToolObservation(
                "call", "search", "search-v1", Map.of("query", "camp"), false,
                AgentToolResult.success(Map.of("answer", "ok"), "trace"), 7);
    }

    private static AgentRunTrace trace(List<AgentRunTrace.StepTrace> steps) {
        return new AgentRunTrace(
                "00000000-0000-0000-0000-000000000001",
                "request", "session", "turn",
                new AgentRunTrace.DefinitionSnapshot(
                        "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                        3, 2, 2000, Map.of("search", "search-v1")),
                NOW, 12, AgentTerminalState.RESULTS_READY, "AGENT", null,
                new LlmUsage(10, 5, 15, 3, true), steps);
    }

    private Map<String, Object> json(Map<String, Object> value) throws Exception {
        return objectMapper.readValue(objectMapper.writeValueAsBytes(value), MAP);
    }
}
