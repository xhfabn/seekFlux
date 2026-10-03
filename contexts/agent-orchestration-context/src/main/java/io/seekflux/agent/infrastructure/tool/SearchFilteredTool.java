package io.seekflux.agent.infrastructure.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.in.SearchUseCase;
import java.util.List;
import java.util.Map;

public final class SearchFilteredTool implements AgentTool {

    public static final String NAME = "search_filtered";
    private static final AgentToolSchema SCHEMA = new AgentToolSchema(
            "search-filtered-tool-v2",
            Map.of(
                    "query", AgentToolParameter.requiredString(500).withDescription("用户要搜索的关键词或主题，不得编造目标。"),
                    "page", AgentToolParameter.optionalInteger(0, 199).withDescription("从 0 开始的页码，省略时为 0。"),
                    "size", AgentToolParameter.optionalInteger(1, 50).withDescription("每页候选数量，省略时为 12。"),
                    "required_tags", AgentToolParameter.optionalStringList(10, 64)
                            .withDescription("从用户需求中确定的标签过滤条件，不得臆造标签；省略时不增加标签约束。")));

    private final SearchUseCase search;

    public SearchFilteredTool(SearchUseCase search) {
        this.search = search;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "面向明确标签约束的内容搜索，使用用户提供的关键词与标签条件。与通用搜索共用 SearchUseCase，返回真实候选和 Search Trace，不自行重排结果。";
    }

    @Override
    public AgentToolSchema schema() {
        return SCHEMA;
    }

    @Override
    public Effect effect() {
        return Effect.READ_ONLY;
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
