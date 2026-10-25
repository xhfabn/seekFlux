package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.*;
import java.util.*;
import static io.seekflux.platform.agenteval.Evaluation.*;

/** Cross-checks independently recorded messages, events and trace identities. */
public final class RuntimeInvariantsGrader implements Grader {
    public String id() { return "runtime-invariants"; }
    public String version() { return "1"; }
    public List<Finding> grade(Case c, Evidence evidence) {
        var root = EvalJson.tree(evidence.data());
        for (String field : List.of("trace", "events", "messages", "state"))
            if (!root.has(field)) return List.of(new Finding(field, Status.ERROR, "required Runtime evidence missing"));
        var trace = root.get("trace"); var events = root.get("events");
        List<Finding> results = new ArrayList<>();
        check(results, "terminal", root.get("state").equals(trace.get("terminalState")), "result and trace terminal state agree");
        check(results, "envelope", events.size() >= 2 && events.get(0).path("type").asText().equals("RUN_STARTED")
                && events.get(events.size() - 1).path("type").asText().equals("RUN_COMPLETED"), "run start and terminal events");
        Set<String> eventIds = new HashSet<>();
        int sequence = -1; boolean valid = true; int terminals = 0;
        for (var event : events) {
            valid &= eventIds.add(event.path("eventId").asText()) && event.path("sequence").asInt() > sequence
                    && event.path("agentRunId").equals(trace.path("agentRunId"))
                    && event.path("requestId").equals(trace.path("requestId"))
                    && event.path("sessionId").equals(trace.path("sessionId"))
                    && event.path("turnId").equals(trace.path("turnId"));
            sequence = event.path("sequence").asInt();
            if (event.path("type").asText().equals("RUN_COMPLETED")) terminals++;
        }
        check(results, "events", valid && terminals == 1, "unique ordered events with matching run/request/session/turn");
        Set<String> messages = new HashSet<>(), calls = new HashSet<>(), returned = new HashSet<>();
        Map<String, String> toolNames = new HashMap<>();
        valid = true;
        for (var message : root.get("messages")) {
            valid &= messages.add(message.path("messageId").asText())
                    && message.path("agentRunId").equals(trace.path("agentRunId"))
                    && message.path("requestId").equals(trace.path("requestId"))
                    && message.path("turnId").equals(trace.path("turnId"));
            for (var call : message.path("toolCalls")) {
                String id = call.path("toolCallId").asText();
                valid &= calls.add(id); toolNames.put(id, call.path("toolName").asText());
            }
            if (message.has("toolCallId")) {
                String id = message.get("toolCallId").asText();
                String name = message.path("toolName").asText();
                valid &= calls.contains(id) && returned.add(id) && name.equals(toolNames.get(id))
                        && message.path("toolSchemaVersion").equals(trace.path("definition").path("toolSchemaVersions").path(name));
            }
        }
        check(results, "messages", valid && calls.equals(returned),
                "unique messages; every call/result pair matches identity, tool name and frozen schema");
        return results;
    }
    private void check(List<Finding> results, String id, boolean pass, String detail) {
        results.add(new Finding(id, pass ? Status.PASS : Status.FAIL, detail));
    }
}
