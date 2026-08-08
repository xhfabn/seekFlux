package io.seekflux.apps.agentserver.runtime;

import io.seekflux.agent.port.in.AgentExecutionMode;
import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.agent.port.in.AgentSearchState;
import io.seekflux.agent.port.in.AgentTraceView;
import io.seekflux.agent.port.out.AgentExecutionPort;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.AgentRunTrace;
import io.seekflux.platform.agentruntime.AgentTerminalState;
import io.seekflux.platform.agentruntime.event.DefaultPushEventPublisher;
import io.seekflux.platform.agentruntime.feature.FeatureRequest;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.router.Router;
import io.seekflux.platform.agentruntime.router.RouterResult;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUseCase;
import java.util.Map;

public final class AgentRuntimeExecutionAdapter implements AgentExecutionPort {

    private final Router router;
    private final Map<String, AgentDefinition> definitions;
    private final Map<String, LlmClient> llmClients;
    private final SearchUseCase directSearch;
    private final RedisAgentSessionProjection projection;

    public AgentRuntimeExecutionAdapter(
            Router router,
            Map<String, AgentDefinition> definitions,
            Map<String, LlmClient> llmClients,
            SearchUseCase directSearch,
            RedisAgentSessionProjection projection) {
        this.router = router;
        this.definitions = Map.copyOf(definitions);
        this.llmClients = Map.copyOf(llmClients);
        this.directSearch = directSearch;
        this.projection = projection;
    }

    @Override
    public AgentSearchResult execute(AgentExecutionRequest request) {
        AgentDefinition definition = definitions.get(request.agentId());
        if (definition == null) {
            throw new IllegalArgumentException("unknown agent definition: " + request.agentId());
        }
        LlmClient llmClient = llmClients.get(request.agentId());
        if (llmClient == null) {
            throw new IllegalStateException("agent definition has no decision provider: " + request.agentId());
        }
        var constraints = request.goal().constraints();
        AgentRunRequest runRequest = new AgentRunRequest(
                request.requestId(),
                request.sessionId(),
                request.turnId(),
                request.goal().query(),
                Map.of(
                        "page", constraints.page(),
                        "size", constraints.size(),
                        "requiredTags", constraints.requiredTags(),
                        "allowClarification", request.allowClarification()));
        RouterResult routed = router.execute(
                new FeatureRequest(definition, runRequest, llmClient),
                new DefaultPushEventPublisher());
        if (routed.status() == RouterResult.Status.BUSY) {
            throw new AgentSessionBusyException();
        }
        if (routed.status() == RouterResult.Status.DUPLICATE) {
            throw new DuplicateAgentRequestException();
        }
        if (routed.status() != RouterResult.Status.COMPLETED || routed.outcome() == null) {
            throw new IllegalStateException("agent request was rejected before execution");
        }

        AgentRunResult runtime = routed.outcome();
        SearchResultPage searchResult = searchResult(runtime.output());
        AgentSearchState state = mapState(runtime.state());
        AgentExecutionMode mode = AgentExecutionMode.AGENT;
        boolean degraded = runtime.degraded();
        if (runtime.state() == AgentTerminalState.FALLBACK_REQUIRED) {
            searchResult = directSearch.search(request.goal().toSearchQuery());
            state = AgentSearchState.FALLBACK_RESULTS;
            mode = AgentExecutionMode.AGENT_TO_DIRECT_FALLBACK;
            degraded = true;
        }
        if (searchResult != null && searchResult.trace().degraded()) {
            degraded = true;
        }

        AgentSearchResult result = new AgentSearchResult(
                request.requestId(),
                runtime.trace().agentRunId(),
                request.sessionId(),
                request.turnId(),
                state,
                mode,
                constraints,
                runtime.clarification(),
                searchResult,
                degraded,
                runtime.fallbackReason(),
                traceView(runtime.trace(), mode));
        projection.project(result);
        return result;
    }

    private static SearchResultPage searchResult(Map<String, Object> output) {
        Object result = output.get("searchResult");
        return result instanceof SearchResultPage searchResult ? searchResult : null;
    }

    private static AgentSearchState mapState(AgentTerminalState state) {
        return switch (state) {
            case RESULTS_READY -> AgentSearchState.RESULTS_READY;
            case NEED_CLARIFICATION -> AgentSearchState.NEED_CLARIFICATION;
            case FALLBACK_REQUIRED -> AgentSearchState.FALLBACK_RESULTS;
            case CANCELLED -> AgentSearchState.CANCELLED;
            case FAILED -> AgentSearchState.FAILED;
        };
    }

    private static AgentTraceView traceView(AgentRunTrace trace, AgentExecutionMode mode) {
        var definition = trace.definition();
        return new AgentTraceView(
                trace.agentRunId(),
                definition.id(),
                definition.version(),
                definition.plannerVersion(),
                definition.promptVersion(),
                definition.decisionProviderVersion(),
                definition.toolSchemaVersions(),
                trace.startedAt(),
                trace.tookMillis(),
                trace.terminalState().name(),
                mode.name(),
                trace.fallbackReason(),
                trace.steps().stream().map(AgentRuntimeExecutionAdapter::stepView).toList());
    }

    private static AgentTraceView.StepView stepView(AgentRunTrace.StepTrace step) {
        return new AgentTraceView.StepView(
                step.step(),
                step.action(),
                step.status(),
                step.toolCallId(),
                step.toolName(),
                step.linkedTraceId(),
                step.tookMillis(),
                step.errorCode());
    }
}
