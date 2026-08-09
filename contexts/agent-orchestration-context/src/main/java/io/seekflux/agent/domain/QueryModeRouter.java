package io.seekflux.agent.domain;

import io.seekflux.agent.port.in.AgentRequestedMode;

public final class QueryModeRouter {

    public Decision route(AgentRequestedMode requestedMode, boolean constraintPatch, SearchPlan plan) {
        if (constraintPatch && requestedMode == AgentRequestedMode.DIRECT) {
            throw new IllegalArgumentException("a multi-turn constraint patch cannot bypass the Agent");
        }
        return switch (requestedMode) {
            case DIRECT -> new Decision(Route.DIRECT, "EXPLICIT_DIRECT");
            case AGENT -> new Decision(Route.AGENT, "EXPLICIT_AGENT");
            case AUTO -> constraintPatch || plan.complex()
                    ? new Decision(Route.AGENT, constraintPatch ? "MULTI_TURN_PATCH" : "COMPLEX_QUERY")
                    : new Decision(Route.DIRECT, "SIMPLE_QUERY");
        };
    }

    public enum Route {
        DIRECT,
        AGENT
    }

    public record Decision(Route route, String reason) {
    }
}
