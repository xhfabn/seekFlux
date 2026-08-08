package io.seekflux.agent.domain;

import io.seekflux.search.port.in.SearchQuery;

public record SearchGoal(String query, QueryConstraintSet constraints) {

    public SearchGoal {
        if (constraints == null) {
            throw new IllegalArgumentException("query constraints must not be null");
        }
        SearchQuery normalized = new SearchQuery(
                query,
                constraints.page(),
                constraints.size(),
                constraints.requiredTags());
        query = normalized.text();
    }

    public SearchQuery toSearchQuery() {
        return new SearchQuery(query, constraints.page(), constraints.size(), constraints.requiredTags());
    }
}
