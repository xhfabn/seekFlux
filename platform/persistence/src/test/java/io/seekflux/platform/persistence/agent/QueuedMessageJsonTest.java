package io.seekflux.platform.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.command.AgentIngressMode;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.session.SessionStatePatch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueuedMessageJsonTest {

    @Test
    void roundTripsTheQueuedRequestAcrossTheJsonPersistenceBoundary() throws Exception {
        AgentRunRequest source = new AgentRunRequest(
                "request-2",
                "session-1",
                "turn-2",
                "换成适合儿童的",
                Map.of(
                        "userId", "user-2",
                        "allowedTools", List.of("search_direct")),
                new SessionStatePatch(
                        7,
                        Map.of("query", "适合儿童", "requiredTags", List.of("亲子"))),
                AgentIngressMode.STEER);
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> persistentFeatures = Map.of(
                "identity", Map.of("userId", "user-2"),
                "persistent", true);
        Map<String, Object> payload = mapper.readValue(
                mapper.writeValueAsBytes(JdbcAgentSessionStore.queuedPayload(
                        source, persistentFeatures)),
                new TypeReference<>() { });

        AgentRunRequest restored = JdbcAgentSessionStore.queuedRequest(
                source.requestId(), source.turnId(), source.input(), payload);

        assertEquals(source, restored);
        assertEquals(persistentFeatures, JdbcAgentSessionStore.queuedFeatures(payload));
    }
}
