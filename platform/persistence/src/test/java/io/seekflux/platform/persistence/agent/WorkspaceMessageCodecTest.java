package io.seekflux.platform.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.service.context.DefaultContextEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceMessageCodecTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final WorkspaceMessageCodec codec = new WorkspaceMessageCodec();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripsAssistantToolCallThroughJsonAndIgnoresUnknownPayloadFields() throws Exception {
        AgentMessage.Assistant source = new AgentMessage.Assistant(
                1,
                "assistant-message-1",
                "request-1",
                "turn-1",
                "run-1",
                1,
                "准备搜索",
                "private reasoning",
                false,
                List.of(new AgentMessage.ToolCall(
                        "call-1", "search_direct", 0, Map.of("query", "露营"))));

        WorkspaceMessageCodec.EncodedMessage encoded = codec.encode(source);
        Map<String, Object> persisted = jsonRoundTrip(encoded.payload());
        persisted = new LinkedHashMap<>(persisted);
        persisted.put("futureField", Map.of("version", 2));
        AgentMessage.Assistant restored = (AgentMessage.Assistant) codec.decode(
                encoded.eventType(),
                encoded.schemaVersion(),
                encoded.messageId(),
                encoded.toolCallId(),
                encoded.requestId(),
                encoded.turnId(),
                persisted);

        assertEquals(source, restored);
        assertFalse(restored.reasoningReplayable());
        assertEquals("call-1", restored.toolCalls().getFirst().toolCallId());
    }

    @Test
    void roundTripsAllToolResultViewsThroughJson() throws Exception {
        AgentMessage.ToolResult source = new AgentMessage.ToolResult(
                1,
                "tool-result-message-1",
                "request-1",
                "turn-1",
                "run-1",
                1,
                "call-1",
                "search_direct",
                "schema-v1",
                AgentMessage.ToolResultStatus.SUCCEEDED,
                "search_direct:{answer=营地}",
                Map.of("raw", "营地"),
                Map.of("title", "营地卡片"),
                Map.of("answer", "营地"),
                List.of(Map.of("uri", "https://example.invalid/item/1")),
                null,
                "search-trace-1",
                false,
                12);

        WorkspaceMessageCodec.EncodedMessage encoded = codec.encode(source);
        AgentMessage.ToolResult restored = (AgentMessage.ToolResult) codec.decode(
                encoded.eventType(),
                encoded.schemaVersion(),
                encoded.messageId(),
                encoded.toolCallId(),
                encoded.requestId(),
                encoded.turnId(),
                jsonRoundTrip(encoded.payload()));

        assertEquals(source, restored);
        assertEquals("营地卡片", restored.displayContents().get("title"));
        assertEquals("https://example.invalid/item/1", restored.resources().getFirst().get("uri"));
    }

    @Test
    void secondTurnRebuildsFirstTurnOnlyFromSerializedWorkspaceFacts() throws Exception {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        AgentMessage.Assistant call = new AgentMessage.Assistant(
                1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                "search", null, false,
                List.of(new AgentMessage.ToolCall(
                        "call-1", "search_direct", 0, Map.of("query", "露营"))));
        AgentMessage.ToolResult result = new AgentMessage.ToolResult(
                1, "result-1", "request-1", "turn-1", "run-1", 1,
                "call-1", "search_direct", "schema-v1",
                AgentMessage.ToolResultStatus.SUCCEEDED,
                "search_direct:{answer=营地}",
                Map.of("answer", "营地"), Map.of(), Map.of("answer", "营地"), List.of(),
                null, "trace-1", false, 8);
        AgentMessage.Assistant complete = new AgentMessage.Assistant(
                1, "assistant-2", "request-1", "turn-1", "run-1", 2,
                "已找到营地", null, false, List.of());
        AgentMessage.Assistant restoredCall = roundTrip(call);
        AgentMessage.ToolResult restoredResult = roundTrip(result);
        AgentMessage.Assistant restoredComplete = roundTrip(complete);
        AgentSession restoredSession = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "agent", "v1"),
                new WorkspaceEvent.UserMessage(2, now, "request-1", "turn-1", "找露营"),
                new WorkspaceEvent.AssistantMessage(3, now, restoredCall),
                new WorkspaceEvent.ToolResultMessage(4, now, restoredResult),
                new WorkspaceEvent.AssistantMessage(5, now, restoredComplete),
                new WorkspaceEvent.RunCompleted(
                        6, now, "run-1", AgentTerminalState.RESULTS_READY, null),
                new WorkspaceEvent.UserMessage(7, now, "request-2", "turn-2", "适合亲子吗")));
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("search_direct"), 3, 2, Duration.ofSeconds(1), true);
        AgentRunRequest secondRequest = new AgentRunRequest(
                "request-2", "session-1", "turn-2", "适合亲子吗", Map.of());
        var assembled = new DefaultContextEngine().assemble(
                restoredSession,
                new RuntimeContext(definition, secondRequest, null, Map.of()),
                new AgentDecisionContext(
                        secondRequest, 1, Duration.ofSeconds(1), List.of()));

        assertEquals(List.of("user", "assistant", "tool", "assistant", "user"),
                assembled.messages().stream()
                        .filter(message -> message.messageId() != null)
                        .map(message -> message.role())
                        .toList());
        assertEquals("call-1", assembled.messages().stream()
                .filter(message -> "tool".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .toolCallId());
    }

    @SuppressWarnings("unchecked")
    private <T extends AgentMessage> T roundTrip(T source) throws Exception {
        WorkspaceMessageCodec.EncodedMessage encoded = codec.encode(source);
        return (T) codec.decode(
                encoded.eventType(),
                encoded.schemaVersion(),
                encoded.messageId(),
                encoded.toolCallId(),
                encoded.requestId(),
                encoded.turnId(),
                jsonRoundTrip(encoded.payload()));
    }

    private Map<String, Object> jsonRoundTrip(Map<String, Object> payload) throws Exception {
        return objectMapper.readValue(objectMapper.writeValueAsBytes(payload), MAP_TYPE);
    }
}
