package io.seekflux.apps.agentserver.runtime;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchClarificationPolicy;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentToolObservation;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeterministicSearchLlmClient implements LlmClient {

    public static final String VERSION = "deterministic-search-decision-v1";
    private final SearchClarificationPolicy clarificationPolicy;

    public DeterministicSearchLlmClient(SearchClarificationPolicy clarificationPolicy) {
        this.clarificationPolicy = clarificationPolicy;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public AgentDecision chat(AssembledContext context) {
        var decision = context.decisionContext();
        AgentToolObservation last = decision.observations().isEmpty()
                ? null
                : decision.observations().getLast();
        if (last != null) {
            return last.result().success()
                    ? new AgentDecision.Complete(last.result().output())
                    : new AgentDecision.Fallback(last.result().errorCode());
        }

        Map<String, Object> attributes = decision.request().attributes();
        QueryConstraintSet constraints = new QueryConstraintSet(
                integer(attributes, "page", 0),
                integer(attributes, "size", 12),
                stringList(attributes.get("requiredTags")));
        SearchGoal goal = new SearchGoal(decision.request().input(), constraints);
        boolean allowClarification = Boolean.TRUE.equals(attributes.get("allowClarification"));
        if (clarificationPolicy.needsClarification(goal, allowClarification)) {
            return new AgentDecision.Clarify(clarificationPolicy.question());
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", goal.query());
        arguments.put("page", constraints.page());
        arguments.put("size", constraints.size());
        arguments.put("required_tags", constraints.requiredTags());
        return new AgentDecision.CallTool(SearchDirectTool.NAME, arguments);
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
