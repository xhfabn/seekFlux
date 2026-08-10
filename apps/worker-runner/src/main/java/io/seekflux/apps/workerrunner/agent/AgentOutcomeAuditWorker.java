package io.seekflux.apps.workerrunner.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public final class AgentOutcomeAuditWorker {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AgentOutcomeAuditWorker(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    "agent.run.completed.v1",
                    "agent.run.fallback.v1",
                    "agent.run.cancelled.v1",
                    "agent.run.failed.v1"
            },
            groupId = "seekflux-agent-outcome-audit-v1")
    public void consume(String envelope, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        JsonNode root = parse(envelope);
        JsonNode payload = root.path("payload");
        jdbcClient.sql("""
                        INSERT INTO agent.audit_events (
                            event_id, session_id, agent_run_id, event_type, event_time, payload
                        ) VALUES (
                            :eventId, :sessionId, :agentRunId, :eventType,
                            CAST(:eventTime AS timestamptz), CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", UUID.fromString(required(root, "event_id")))
                .param("sessionId", required(payload, "sessionId"))
                .param("agentRunId", UUID.fromString(required(payload, "agentRunId")))
                .param("eventType", topic)
                .param("eventTime", required(root, "event_time"))
                .param("payload", payload.toString())
                .update();
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid agent outcome envelope", invalid);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("agent outcome is missing " + field);
        }
        return value;
    }
}
