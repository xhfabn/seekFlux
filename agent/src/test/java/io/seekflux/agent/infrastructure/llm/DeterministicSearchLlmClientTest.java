package io.seekflux.agent.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.seekflux.agent.domain.SearchClarificationPolicy;
import io.seekflux.agent.infrastructure.tool.SearchDirectTool;
import io.seekflux.agent.infrastructure.tool.SearchFilteredTool;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicSearchLlmClientTest {

    @Test
    void choosesToolsFromTheFrozenCapabilitySnapshotInsteadOfLegacyAttributes() {
        AgentRunRequest request = new AgentRunRequest(
                "request-1", "session-1", "turn-1", "杭州亲子露营",
                Map.of(
                        "goalQuery", "杭州亲子露营",
                        "page", 0,
                        "size", 5,
                        "requiredTags", List.of("亲子"),
                        "derivedRequiredTags", List.of("露营"),
                        "rewrittenQuery", "杭州 亲子 露营",
                        "allowClarification", false,
                        "allowedTools", List.of(SearchDirectTool.NAME)));
        AgentDecisionContext decision = new AgentDecisionContext(
                request,
                1,
                Duration.ofSeconds(1),
                List.of(),
                ignored -> { },
                ignored -> { },
                "run-1",
                null,
                CapabilitySnapshot.legacy(Set.of(
                        SearchDirectTool.NAME, SearchFilteredTool.NAME)));

        AgentDecision result = new DeterministicSearchLlmClient(
                new SearchClarificationPolicy()).chat(
                        new AssembledContext(decision, List.of(), "spec-v1", 1));

        assertThat(result).isInstanceOf(AgentDecision.CallTools.class);
        assertThat(((AgentDecision.CallTools) result).calls())
                .extracting(AgentDecision.ToolCall::toolName)
                .containsExactly(SearchDirectTool.NAME, SearchFilteredTool.NAME);
    }
}
