package io.seekflux.agent.domain;

import java.util.List;

public final class SearchToolPolicy {

    public static final String DIRECT_TOOL = "search_direct";
    public static final String FILTERED_TOOL = "search_filtered";
    public static final String VERSION = "search-tool-policy-v1";

    public List<String> exposedTools(SearchPlan plan) {
        if (plan.complex() && !plan.derivedRequiredTags().isEmpty()) {
            return List.of(DIRECT_TOOL, FILTERED_TOOL);
        }
        return List.of(DIRECT_TOOL);
    }
}
