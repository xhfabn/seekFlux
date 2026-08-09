package io.seekflux.agent.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SearchIntentAnalyzer {

    public static final String VERSION = "search-intent-rules-v1";
    private static final List<String> KNOWN_TAGS = List.of(
            "杭州", "上海", "北京", "西湖", "亲子", "露营", "咖啡", "手冲", "AI", "办公",
            "摄影", "夜景", "旅行", "家常菜", "美食", "猫咪", "宠物", "护理", "教程", "知识",
            "新手", "周末", "室内", "户外", "五分钟内", "三分钟内", "最近一周");
    private static final List<String> COMPLEX_MARKERS = List.of(
            "只看", "不要", "排除", "最近", "以内", "附近", "适合", "放宽", "改成", "同时", "并且");

    public SearchPlan analyze(SearchGoal goal) {
        String query = goal.query();
        String positiveClause = positiveClause(query);
        Set<String> tags = new LinkedHashSet<>();
        KNOWN_TAGS.stream().filter(positiveClause::contains).forEach(tags::add);
        List<String> reasons = new ArrayList<>();
        if (query.codePointCount(0, query.length()) >= 16) {
            reasons.add("LONG_NATURAL_LANGUAGE_QUERY");
        }
        if (COMPLEX_MARKERS.stream().anyMatch(query::contains)) {
            reasons.add("EXPLICIT_CONSTRAINT_LANGUAGE");
        }
        if (tags.size() >= 3) {
            reasons.add("MULTI_SLOT_QUERY");
        }
        if (goal.version() > 1) {
            reasons.add("MULTI_TURN_GOAL");
        }
        String rewritten = tags.isEmpty() ? query : String.join(" ", tags);
        return new SearchPlan(query, rewritten, List.copyOf(tags), !reasons.isEmpty(), reasons);
    }

    private static String positiveClause(String query) {
        int boundary = query.length();
        for (String negative : List.of("不要", "排除")) {
            int index = query.indexOf(negative);
            if (index >= 0) {
                boundary = Math.min(boundary, index);
            }
        }
        return query.substring(0, boundary);
    }
}
