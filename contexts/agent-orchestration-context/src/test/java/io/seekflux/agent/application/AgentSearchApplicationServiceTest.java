package io.seekflux.agent.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.QueryModeRouter;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.domain.SearchIntentAnalyzer;
import io.seekflux.agent.domain.SearchToolPolicy;
import io.seekflux.agent.port.in.AgentRequestedMode;
import io.seekflux.agent.port.in.AgentSearchCommand;
import io.seekflux.agent.port.out.AgentConversationPort;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentSearchApplicationServiceTest {

    @Test
    void autoModeKeepsSimpleQueryOnDirectPath() {
        AtomicReference<AgentExecutionRequest> direct = new AtomicReference<>();
        AtomicReference<AgentExecutionRequest> agent = new AtomicReference<>();
        AgentSearchApplicationService service = service(Optional.empty(), direct, agent);

        var result = service.search(command("猫咪护理", AgentRequestedMode.AUTO));

        assertNull(result);
        assertEquals("SIMPLE_QUERY", direct.get().routeReason());
        assertNull(agent.get());
    }

    @Test
    void autoModeRoutesComplexQueryWithDynamicToolSetAndStatePatch() {
        AtomicReference<AgentExecutionRequest> direct = new AtomicReference<>();
        AtomicReference<AgentExecutionRequest> agent = new AtomicReference<>();
        AgentSearchApplicationService service = service(Optional.empty(), direct, agent);

        service.search(command("只看适合亲子的杭州露营教程，不要成人徒步", AgentRequestedMode.AUTO));

        assertNull(direct.get());
        assertEquals("COMPLEX_QUERY", agent.get().routeReason());
        assertEquals(List.of("search_direct", "search_filtered"), agent.get().exposedTools());
        assertEquals(0, agent.get().goalChange().baseVersion());
        assertEquals(List.of("杭州", "亲子", "露营", "教程"),
                agent.get().plan().derivedRequiredTags());
    }

    private static AgentSearchApplicationService service(
            Optional<SearchGoal> current,
            AtomicReference<AgentExecutionRequest> direct,
            AtomicReference<AgentExecutionRequest> agent) {
        AgentConversationPort conversations = ignored -> current;
        return new AgentSearchApplicationService(
                request -> { agent.set(request); return null; },
                request -> { direct.set(request); return null; },
                conversations,
                new QueryModeRouter(),
                new SearchIntentAnalyzer(),
                new SearchToolPolicy());
    }

    private static AgentSearchCommand command(String query, AgentRequestedMode mode) {
        return new AgentSearchCommand(
                "request-1", "session-1", "turn-1", "search-assistant",
                query, 0, 5, List.of(), false, mode, null);
    }
}
