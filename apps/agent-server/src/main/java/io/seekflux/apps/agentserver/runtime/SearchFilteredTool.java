package io.seekflux.apps.agentserver.runtime;

import io.seekflux.platform.agentruntime.AgentTool;
import io.seekflux.platform.agentruntime.AgentToolContext;
import io.seekflux.platform.agentruntime.AgentToolParameter;
import io.seekflux.platform.agentruntime.AgentToolResult;
import io.seekflux.platform.agentruntime.AgentToolSchema;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.in.SearchUseCase;
import java.util.List;
import java.util.Map;

public final class SearchFilteredTool implements AgentTool {

    public static final String NAME = "search_filtered";
    private static final AgentToolSchema SCHEMA = new AgentToolSchema(
            "search-filtered-tool-v1",
            Map.of(
                    "query", AgentToolParameter.requiredString(500),
                    "page", AgentToolParameter.optionalInteger(0, 199),
                    "size", AgentToolParameter.optionalInteger(1, 50),
                    "required_tags", AgentToolParameter.optionalStringList(10, 64)));

    private final SearchUseCase search;

    public SearchFilteredTool(SearchUseCase search) {
        this.search = search;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AgentToolSchema schema() {
        return SCHEMA;
    }

    @Override
    public AgentToolResult execute(AgentToolContext context) {
        try {
            SearchResultPage result = search.search(new SearchQuery(
                    String.valueOf(context.arguments().get("query")),
                    integer(context.arguments(), "page", 0),
                    integer(context.arguments(), "size", 12),
                    stringList(context.arguments().get("required_tags"))));
            return AgentToolResult.success(Map.of("searchResult", result), result.trace().requestId());
        } catch (SearchUnavailableException unavailable) {
            return AgentToolResult.failure("SEARCH_UNAVAILABLE");
        }
    }

    private static int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
