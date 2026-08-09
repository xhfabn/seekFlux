package io.seekflux.agent.domain;

import io.seekflux.search.port.in.SearchQuery;
import java.util.List;
import java.util.Map;

public record QueryConstraintSet(int page, int size, List<String> requiredTags) {

    public QueryConstraintSet {
        SearchQuery validated = new SearchQuery("validation", page, size, requiredTags);
        requiredTags = validated.requiredTags();
    }

    public static QueryConstraintSet firstPage(int size, List<String> requiredTags) {
        return new QueryConstraintSet(0, size, requiredTags);
    }

    public Map<String, Object> toState() {
        return Map.of("page", page, "size", size, "requiredTags", requiredTags);
    }

    public static QueryConstraintSet fromState(Map<String, Object> state) {
        return new QueryConstraintSet(
                integer(state.get("page"), 0),
                integer(state.get("size"), 12),
                stringList(state.get("requiredTags")));
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
