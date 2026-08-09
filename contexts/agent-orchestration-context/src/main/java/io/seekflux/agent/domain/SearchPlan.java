package io.seekflux.agent.domain;

import java.util.List;

public record SearchPlan(
        String originalQuery,
        String rewrittenQuery,
        List<String> derivedRequiredTags,
        boolean complex,
        List<String> reasons) {

    public SearchPlan {
        derivedRequiredTags = derivedRequiredTags == null ? List.of() : List.copyOf(derivedRequiredTags);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
