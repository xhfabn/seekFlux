package io.seekflux.agent.domain;

import io.seekflux.search.port.in.SearchQuery;
import java.util.List;

public record QueryConstraintSet(int page, int size, List<String> requiredTags) {

    public QueryConstraintSet {
        SearchQuery validated = new SearchQuery("validation", page, size, requiredTags);
        requiredTags = validated.requiredTags();
    }

    public static QueryConstraintSet firstPage(int size, List<String> requiredTags) {
        return new QueryConstraintSet(0, size, requiredTags);
    }
}
