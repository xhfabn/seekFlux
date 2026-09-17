package io.seekflux.platform.agentruntime.application.spi.business.planner.model;

import java.util.Map;

@FunctionalInterface
public interface EagerToolDispatcher {

    EagerToolDispatcher DISABLED = (index, toolName, arguments) ->
            new Dispatch(Disposition.DISABLED, null);

    Dispatch dispatch(int index, String toolName, Map<String, Object> arguments);

    enum Disposition { STARTED, DUPLICATE, INELIGIBLE, REJECTED, DISABLED }

    record Dispatch(Disposition disposition, String toolCallId) {
    }
}
