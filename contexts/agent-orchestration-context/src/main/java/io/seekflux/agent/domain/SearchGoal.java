package io.seekflux.agent.domain;

import io.seekflux.search.port.in.SearchQuery;
import java.util.Map;

public record SearchGoal(long version, String query, QueryConstraintSet constraints) {

    public SearchGoal {
        if (version < 1) {
            throw new IllegalArgumentException("search goal version must be positive");
        }
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

    public SearchGoal(String query, QueryConstraintSet constraints) {
        this(1, query, constraints);
    }

    public SearchGoal replace(String nextQuery, QueryConstraintSet nextConstraints) {
        return new SearchGoal(version + 1, nextQuery, nextConstraints);
    }

    public SearchGoal apply(ConstraintPatch patch) {
        if (patch.baseVersion() != version) {
            throw new ConstraintVersionConflictException(patch.baseVersion(), version);
        }
        return new SearchGoal(
                version + 1,
                patch.replacementQuery() == null ? query : patch.replacementQuery(),
                patch.apply(constraints));
    }

    public SearchQuery toSearchQuery() {
        return new SearchQuery(query, constraints.page(), constraints.size(), constraints.requiredTags());
    }

    public Map<String, Object> toState() {
        return Map.of(
                "type", "search_goal_v1",
                "version", version,
                "query", query,
                "constraints", constraints.toState());
    }

    public static SearchGoal fromState(Map<String, Object> state) {
        if (!"search_goal_v1".equals(state.get("type"))) {
            throw new IllegalArgumentException("workspace state is not a supported SearchGoal");
        }
        Object constraints = state.get("constraints");
        if (!(constraints instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("search goal constraints are missing");
        }
        java.util.LinkedHashMap<String, Object> normalized = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        Object version = state.get("version");
        if (!(version instanceof Number number)) {
            throw new IllegalArgumentException("search goal version is missing");
        }
        return new SearchGoal(
                number.longValue(),
                String.valueOf(state.get("query")),
                QueryConstraintSet.fromState(normalized));
    }
}
