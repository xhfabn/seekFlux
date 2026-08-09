package io.seekflux.agent.domain;

import java.util.LinkedHashSet;
import java.util.List;

public record ConstraintPatch(
        long baseVersion,
        String replacementQuery,
        Integer page,
        Integer size,
        List<String> addRequiredTags,
        List<String> removeRequiredTags) {

    public ConstraintPatch {
        if (baseVersion < 1) {
            throw new IllegalArgumentException("constraint patch base version must be positive");
        }
        replacementQuery = normalizeOptional(replacementQuery);
        addRequiredTags = addRequiredTags == null ? List.of() : List.copyOf(addRequiredTags);
        removeRequiredTags = removeRequiredTags == null ? List.of() : List.copyOf(removeRequiredTags);
        if (replacementQuery == null && page == null && size == null
                && addRequiredTags.isEmpty() && removeRequiredTags.isEmpty()) {
            throw new IllegalArgumentException("constraint patch must contain at least one change");
        }
    }

    public QueryConstraintSet apply(QueryConstraintSet current) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(current.requiredTags());
        tags.removeAll(removeRequiredTags);
        tags.addAll(addRequiredTags);
        return new QueryConstraintSet(
                page == null ? current.page() : page,
                size == null ? current.size() : size,
                List.copyOf(tags));
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
