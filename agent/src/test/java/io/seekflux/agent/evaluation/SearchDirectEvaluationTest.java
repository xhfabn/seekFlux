package io.seekflux.agent.evaluation;

import io.seekflux.agent.orchestration.adapter.SearchDirectTool;
import io.seekflux.core.search.api.SearchHitView;
import io.seekflux.core.search.api.SearchQuery;
import io.seekflux.core.search.api.SearchResultPage;
import io.seekflux.core.search.api.SearchTrace;
import io.seekflux.core.search.api.SearchUnavailableException;
import io.seekflux.platform.agenteval.EvalCli;
import io.seekflux.platform.agenteval.EvalJson;
import io.seekflux.platform.agenteval.Evaluation;
import io.seekflux.platform.agenteval.ReportWriter;
import io.seekflux.platform.agenteval.junit.AgentEvaluations;
import io.seekflux.platform.agenteval.runtime.RuntimeEvaluationTarget;
import io.seekflux.platform.agenteval.runtime.RuntimeFixture;
import io.seekflux.platform.agenteval.runtime.ScriptedFixture;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Real SearchDirectTool and Runtime; only the search dependency is replaced with a fixture. */
class SearchDirectEvaluationTest {

    @TestFactory
    Stream<DynamicTest> searchDirectContract() throws Exception {
        // 1. Load explicit inputs, faults and expectations from the business-owned suite.
        Evaluation.Suite suite;
        try (var input = Objects.requireNonNull(
                getClass().getResourceAsStream("/evals/search-direct-v1.json"), "suite resource")) {
            suite = EvalJson.decode(new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    Evaluation.Suite.class);
        }

        // 2. The factory creates a fresh tool and dependency state for every case.
        var target = new RuntimeEvaluationTarget(
                "seekflux-search-direct", "search-direct-tool-v2-fixture-v1", SearchFixture::new);

        // 3. Reuse execution, fault injection, checks and Runtime invariant grading.
        var report = EvalCli.runner().run(suite, target);

        // 4. Persist evidence before exposing failures to JUnit.
        Path output = Path.of("target/evaluation/search-direct-v1");
        EvalJson.write(output.resolve("suite.json"), suite);
        ReportWriter.write(output, report);
        return AgentEvaluations.tests(report);
    }

    private static final class SearchFixture implements RuntimeFixture {
        private final ScriptedFixture script;
        private final List<SearchQuery> requests = new CopyOnWriteArrayList<>();
        private final SearchDirectTool tool;

        private SearchFixture(Evaluation.Case testCase) {
            script = new ScriptedFixture(testCase);
            String backend = (String) testCase.parameters().getOrDefault("searchBackend", "fixed");
            if (!List.of("fixed", "unavailable").contains(backend)) {
                throw new IllegalArgumentException("unknown fixture search backend: " + backend);
            }
            tool = new SearchDirectTool(query -> {
                requests.add(query);
                // Dependency failure exercises the tool's own exception mapping.
                // BEFORE_TOOL/AFTER_TOOL faults remain the framework's responsibility.
                if ("unavailable".equals(backend)) {
                    throw new SearchUnavailableException();
                }
                return fixedPage(query);
            });
        }

        @Override
        public Collection<? extends AgentTool> tools() {
            return List.of(tool);
        }

        @Override
        public AgentPlanner planner() {
            return script.planner();
        }

        @Override
        public AgentDefinition definition() {
            return script.definition();
        }

        @Override
        public Map<String, Object> effects() {
            // Independent evidence of whether search was called and with which arguments.
            var snapshot = List.copyOf(requests);
            return Map.of("searchCalls", snapshot.size(), "requests", snapshot);
        }

        private static SearchResultPage fixedPage(SearchQuery query) {
            var hit = new SearchHitView(
                    "camp-001", "fixture-author", "ARTICLE", "https://example.invalid/camp-001",
                    List.of(), "亲子露营指南", "固定评测样例", "适合亲子活动的露营准备清单。",
                    "亲子露营准备清单", List.of("亲子", "周末"), "fixture", "", "", "",
                    1, 1.0, List.of("fixture"), Instant.parse("2026-01-01T00:00:00Z"));
            var trace = new SearchTrace(
                    "search-eval-trace-v1", "DIRECT", "fixture-index-v1", "fixture-policy-v1",
                    1, false, List.of(), List.of());
            return new SearchResultPage(query.text(), 1, query.page(), query.size(), 1, List.of(hit), trace);
        }
    }
}
