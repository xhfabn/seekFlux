package io.seekflux.apps.agentserver.runtime;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchClarificationPolicy;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentToolObservation;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import io.seekflux.search.port.in.SearchResultPage;

public final class DeterministicSearchLlmClient implements LlmClient {

    public static final String VERSION = "deterministic-complex-search-decision-v2";
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
        if (!decision.observations().isEmpty()) {
            List<AgentToolObservation> successful = decision.observations().stream()
                    .filter(observation -> observation.result().success())
                    .toList();
            if (successful.isEmpty()) {
                return new AgentDecision.Fallback(decision.observations().getFirst().result().errorCode());
            }
            AgentToolObservation selected = successful.stream()
                    .filter(observation -> SearchFilteredTool.NAME.equals(observation.toolName()))
                    .filter(DeterministicSearchLlmClient::hasResults)
                    .findFirst()
                    .orElseGet(() -> successful.stream()
                            .filter(DeterministicSearchLlmClient::hasResults)
                            .findFirst()
                            .orElse(successful.getFirst()));
            Map<String, Object> output = new LinkedHashMap<>(selected.result().output());
            output.put("selectedTool", selected.toolName());
            output.put("successfulToolCount", successful.size());
            output.put("candidateSetReused", true);
            return new AgentDecision.Complete(output);
        }

        Map<String, Object> attributes = decision.request().attributes();
        QueryConstraintSet constraints = new QueryConstraintSet(
                integer(attributes, "page", 0),
                integer(attributes, "size", 12),
                stringList(attributes.get("requiredTags")));
        SearchGoal goal = new SearchGoal(
                String.valueOf(attributes.getOrDefault("goalQuery", decision.request().input())),
                constraints);
        boolean allowClarification = Boolean.TRUE.equals(attributes.get("allowClarification"));
        if (clarificationPolicy.needsClarification(goal, allowClarification)) {
            return new AgentDecision.Clarify(clarificationPolicy.question());
        }

        Map<String, Object> direct = arguments(goal.query(), constraints, constraints.requiredTags());
        List<String> allowedTools = stringList(attributes.get("allowedTools"));
        if (!allowedTools.contains(SearchFilteredTool.NAME)) {
            return new AgentDecision.CallTool(SearchDirectTool.NAME, direct);
        }
        LinkedHashSet<String> filteredTags = new LinkedHashSet<>(constraints.requiredTags());
        filteredTags.addAll(stringList(attributes.get("derivedRequiredTags")));
        String rewritten = String.valueOf(attributes.getOrDefault("rewrittenQuery", goal.query()));
        Map<String, Object> filtered = arguments(rewritten, constraints, List.copyOf(filteredTags));
        return new AgentDecision.CallTools(List.of(
                new AgentDecision.ToolCall(SearchDirectTool.NAME, direct),
                new AgentDecision.ToolCall(SearchFilteredTool.NAME, filtered)));
    }

    private static Map<String, Object> arguments(
            String query,
            QueryConstraintSet constraints,
            List<String> requiredTags) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);
        arguments.put("page", constraints.page());
        arguments.put("size", constraints.size());
        arguments.put("required_tags", requiredTags);
        return arguments;
    }

    private static boolean hasResults(AgentToolObservation observation) {
        Object value = observation.result().output().get("searchResult");
        return value instanceof SearchResultPage page && !page.hits().isEmpty();
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
