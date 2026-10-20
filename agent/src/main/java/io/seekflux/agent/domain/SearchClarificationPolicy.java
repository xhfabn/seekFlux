package io.seekflux.agent.domain;

import java.util.Set;

public final class SearchClarificationPolicy {

    private static final Set<String> VAGUE_QUERIES = Set.of(
            "推荐一下", "随便看看", "这个", "那个", "都可以", "你看着办");

    public boolean needsClarification(SearchGoal goal, boolean allowClarification) {
        return allowClarification && VAGUE_QUERIES.contains(goal.query());
    }

    public String question() {
        return "你想看哪一类内容？可以补充主题、地点或用途。";
    }
}
