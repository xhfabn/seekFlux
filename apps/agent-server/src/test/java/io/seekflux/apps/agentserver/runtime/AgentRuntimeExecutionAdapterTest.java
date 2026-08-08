package io.seekflux.apps.agentserver.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.port.in.AgentExecutionMode;
import io.seekflux.agent.port.in.AgentSearchState;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.AgentRunTrace;
import io.seekflux.platform.agentruntime.AgentTerminalState;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.router.Router;
import io.seekflux.platform.agentruntime.router.RouterResult;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchTrace;
import io.seekflux.search.port.in.SearchUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeExecutionAdapterTest {

    @Test
    void fallsBackThroughTheSameDirectSearchUseCase() {
        Router router = mock(Router.class);
        SearchUseCase directSearch = mock(SearchUseCase.class);
        RedisAgentSessionProjection projection = mock(RedisAgentSessionProjection.class);
        LlmClient llm = mock(LlmClient.class);
        AgentDefinition definition = new AgentDefinition(
                "search-assistant",
                "search-assistant-v1",
                "default-react-loop-v1",
                "search-agent-prompt-v1",
                "decision-v1",
                Set.of("search_direct"),
                3,
                1,
                Duration.ofSeconds(2),
                true);
        AgentRunTrace trace = new AgentRunTrace(
                "00000000-0000-0000-0000-000000000001",
                "request-1",
                "session-1",
                "turn-1",
                new AgentRunTrace.DefinitionSnapshot(
                        definition.id(),
                        definition.version(),
                        definition.plannerVersion(),
                        definition.promptVersion(),
                        definition.decisionProviderVersion(),
                        definition.maxSteps(),
                        definition.maxToolCalls(),
                        definition.timeout().toMillis(),
                        Map.of("search_direct", "search-direct-tool-v1")),
                Instant.parse("2026-08-08T00:00:00Z"),
                10,
                AgentTerminalState.FALLBACK_REQUIRED,
                "FALLBACK_REQUIRED",
                "AGENT_DEADLINE_EXCEEDED",
                List.of(new AgentRunTrace.StepTrace(
                        1, "FALLBACK", "REQUIRED", null, null, null, 0, "AGENT_DEADLINE_EXCEEDED")));
        when(router.execute(any(), any())).thenReturn(RouterResult.completed(new AgentRunResult(
                AgentTerminalState.FALLBACK_REQUIRED,
                Map.of(),
                null,
                "AGENT_DEADLINE_EXCEEDED",
                true,
                trace)));
        SearchResultPage directResult = new SearchResultPage(
                "杭州亲子露营",
                0,
                0,
                5,
                3,
                List.of(),
                new SearchTrace(
                        "search-trace-1",
                        "DIRECT_HYBRID",
                        "seekflux-content-v1",
                        "direct-hybrid-v1",
                        3,
                        false,
                        List.of(),
                        List.of()));
        when(directSearch.search(any(SearchQuery.class))).thenReturn(directResult);
        AgentRuntimeExecutionAdapter adapter = new AgentRuntimeExecutionAdapter(
                router,
                Map.of(definition.id(), definition),
                Map.of(definition.id(), llm),
                directSearch,
                projection);

        var result = adapter.execute(new AgentExecutionRequest(
                "request-1",
                "session-1",
                "turn-1",
                definition.id(),
                new SearchGoal("杭州亲子露营", QueryConstraintSet.firstPage(5, List.of())),
                true));

        assertThat(result.state()).isEqualTo(AgentSearchState.FALLBACK_RESULTS);
        assertThat(result.executionMode()).isEqualTo(AgentExecutionMode.AGENT_TO_DIRECT_FALLBACK);
        assertThat(result.searchResult()).isSameAs(directResult);
        assertThat(result.fallbackReason()).isEqualTo("AGENT_DEADLINE_EXCEEDED");
        assertThat(result.degraded()).isTrue();
        verify(directSearch).search(any(SearchQuery.class));
        verify(projection).project(result);
    }
}
