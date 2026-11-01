package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.Evaluation;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.*;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import java.util.*;

/** A fresh fixture per case. Tool implementations need no fault-injection code. */
public interface RuntimeFixture extends AutoCloseable {
    Collection<? extends AgentTool> tools();
    AgentPlanner planner();
    AgentDefinition definition();
    default ToolExecutionPolicy policy() { return ToolExecutionPolicy.ALLOW_ALL; }
    default Map<String, Object> effects() { return Map.of(); }
    @Override default void close() throws Exception {}
    @FunctionalInterface interface Factory { RuntimeFixture create(Evaluation.Case testCase) throws Exception; }
}
