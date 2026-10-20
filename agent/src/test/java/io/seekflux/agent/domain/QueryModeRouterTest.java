package io.seekflux.agent.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seekflux.agent.port.in.AgentRequestedMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryModeRouterTest {

    private final QueryModeRouter router = new QueryModeRouter();

    @Test
    void routesSimpleAutoQueryDirectlyAndComplexQueryToAgent() {
        SearchPlan simple = new SearchPlan("猫咪护理", "猫咪 护理", List.of("猫咪", "护理"), false, List.of());
        SearchPlan complex = new SearchPlan(
                "只看杭州亲子露营教程",
                "杭州 亲子 露营 教程",
                List.of("杭州", "亲子", "露营", "教程"),
                true,
                List.of("MULTI_SLOT_QUERY"));

        assertEquals(QueryModeRouter.Route.DIRECT,
                router.route(AgentRequestedMode.AUTO, false, simple).route());
        assertEquals(QueryModeRouter.Route.AGENT,
                router.route(AgentRequestedMode.AUTO, false, complex).route());
    }

    @Test
    void doesNotAllowDirectModeToBypassMultiTurnPatch() {
        SearchPlan plan = new SearchPlan("露营", "露营", List.of("露营"), true, List.of("MULTI_TURN_GOAL"));

        assertThrows(IllegalArgumentException.class,
                () -> router.route(AgentRequestedMode.DIRECT, true, plan));
    }
}
