package io.seekflux.platform.agenteval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.seekflux.platform.agenteval.Evaluation.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {
    @TempDir Path directory;
    private Case sample(String id) {
        return new Case(id, "test", Set.of("basic"), Map.of(), List.of(),
                List.of(new Check("state", "/state", Operator.EQUALS, "ok")), 2000);
    }
    private Suite suite(Case... cases) { return new Suite("tests", "1", Map.of(), List.of(cases)); }
    private EvaluationTarget target(Throwing action, AtomicInteger closed) {
        return new EvaluationTarget() {
            public String id() { return "test"; }
            public String version() { return "1"; }
            public Set<String> capabilities() { return Set.of("basic"); }
            public Session open(Case c, FaultController f) {
                return new Session() {
                    public Evidence execute() throws Exception { return action.run(c); }
                    public void close() { closed.incrementAndGet(); }
                };
            }
        };
    }
    @FunctionalInterface interface Throwing { Evidence run(Case c) throws Exception; }
    @Test void executionErrorsStayInDenominatorAndCleanupRuns() {
        var closed = new AtomicInteger();
        var report = new EvaluationRunner().run(suite(sample("bad"), sample("good")),
                target(c -> { if (c.id().equals("bad")) throw new IllegalStateException("broken"); return new Evidence(Map.of("state", "ok")); }, closed));
        assertEquals(2, report.results().size());
        assertEquals(Status.ERROR, report.results().getFirst().status());
        assertEquals(Status.PASS, report.results().getLast().status());
        assertEquals(2, closed.get());
    }
    @Test void timeoutInterruptsTargetAndPreservesFollowingCase() {
        var c = sample("slow");
        var bounded = new Case(c.id(), c.input(), c.requiredCapabilities(), c.parameters(), c.faults(), c.checks(), 100);
        var closed = new AtomicInteger();
        var report = new EvaluationRunner().run(suite(bounded, sample("good")), target(test -> {
            if (test.id().equals("slow")) Thread.sleep(10_000);
            return new Evidence(Map.of("state", "ok"));
        }, closed));
        assertEquals(Status.ERROR, report.results().getFirst().status());
        assertEquals(Status.PASS, report.results().getLast().status());
        assertEquals(2, closed.get());
        assertTrue(report.results().getFirst().tookMillis() < 2000);
    }
    @Test void missingCapabilityNeverStartsTarget() {
        var c = sample("durable");
        var calls = new AtomicInteger();
        var report = new EvaluationRunner().run(suite(new Case(c.id(), c.input(), Set.of("durable"),
                c.parameters(), c.faults(), c.checks(), c.timeoutMillis())), target(test -> {
                    calls.incrementAndGet(); return new Evidence(Map.of("state", "ok"));
                }, new AtomicInteger()));
        assertEquals(0, calls.get()); assertEquals(Status.ERROR, report.results().getFirst().status());
    }
    @Test void uncooperativeTargetAbortsRemainingCasesInsteadOfPretendingCleanupSucceeded() {
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new AtomicInteger();
        var c = sample("stuck");
        var bounded = new Case(c.id(), c.input(), c.requiredCapabilities(), c.parameters(), c.faults(), c.checks(), 100);
        try {
            var report = new EvaluationRunner().run(suite(bounded, sample("next")), target(test -> {
                calls.incrementAndGet();
                while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
                return new Evidence(Map.of("state", "ok"));
            }, new AtomicInteger()));
            assertEquals(1, calls.get());
            assertEquals(2, report.count(Status.ERROR));
            assertTrue(report.results().getLast().findings().getFirst().detail().contains("RUN_ABORTED"));
        } finally { release.countDown(); }
    }
    @Test void invariantGraderRejectsMissingToolResults() {
        var report = EvalCli.runner().run(DefaultSuite.load(), EvalCli.target());
        var lookup = report.results().stream().filter(r -> r.caseId().equals("lookup")).findFirst().orElseThrow();
        var data = new HashMap<>(lookup.evidence().data());
        var messages = (com.fasterxml.jackson.databind.node.ArrayNode) EvalJson.tree(data.get("messages"));
        messages.remove(1); data.put("messages", messages);
        var findings = new io.seekflux.platform.agenteval.runtime.RuntimeInvariantsGrader().grade(sample("broken"), new Evidence(data));
        assertTrue(findings.stream().anyMatch(f -> f.id().equals("messages") && f.status() == Status.FAIL));
    }
    @Test void unhitFaultIsFailureEvenWithCorrectAnswer() {
        var c = sample("missed");
        var report = new EvaluationRunner().run(suite(new Case(c.id(), c.input(), c.requiredCapabilities(), c.parameters(),
                List.of(new Fault("miss", Phase.BEFORE_TOOL, "absent", 1, FaultAction.THROW, 0)), c.checks(), c.timeoutMillis())),
                target(test -> new Evidence(Map.of("state", "ok")), new AtomicInteger()));
        assertEquals(Status.FAIL, report.results().getFirst().status());
        assertEquals(0, report.results().getFirst().faultHits().get("miss"));
    }
    @Test void absentEvidenceCannotPassAsAnEmptyValue() {
        var report = new EvaluationRunner().run(suite(sample("missing")), target(c -> new Evidence(Map.of()), new AtomicInteger()));
        assertEquals(Status.ERROR, report.results().getFirst().status());
    }
    @Test void graderErrorCannotTurnIntoZeroOrSuccess() {
        Grader grader = new Grader() {
            public String id() { return "business"; }
            public String version() { return "1"; }
            public List<Finding> grade(Case c, Evidence e) { throw new IllegalStateException("judge unavailable"); }
        };
        var report = new EvaluationRunner(List.of(grader)).run(suite(sample("judge")),
                target(c -> new Evidence(Map.of("state", "ok")), new AtomicInteger()));
        assertEquals(Status.ERROR, report.results().getFirst().status());
    }
    @Test void graderAlsoHasCaseDeadline() {
        Grader grader = new Grader() {
            public String id() { return "slow"; }
            public String version() { return "1"; }
            public List<Finding> grade(Case c, Evidence e) throws Exception { Thread.sleep(10_000); return List.of(); }
        };
        var c = sample("judge");
        var bounded = new Case(c.id(), c.input(), c.requiredCapabilities(), c.parameters(), c.faults(), c.checks(), 100);
        var report = new EvaluationRunner(List.of(grader)).run(suite(bounded), target(test -> new Evidence(Map.of("state", "ok")), new AtomicInteger()));
        assertEquals(Status.ERROR, report.results().getFirst().status());
        assertTrue(report.results().getFirst().tookMillis() < 2000);
    }
    @Test void reportRoundTripRescoresWithoutCallingTarget() throws Exception {
        var runner = new EvaluationRunner(); var suite = suite(sample("stored"));
        var report = runner.run(suite, target(c -> new Evidence(Map.of("state", "ok")), new AtomicInteger()));
        ReportWriter.write(directory, report);
        var read = EvalJson.read(directory.resolve("report.json"), Report.class);
        var rescored = runner.rescore(suite, read);
        assertTrue(rescored.passed()); assertEquals(report.runId(), rescored.metadata().get("rescoredFrom"));
        assertEquals(1, Files.readAllLines(directory.resolve("cases.jsonl")).size());
    }
    @Test void mismatchedSuiteCannotBeComparedOrRescored() {
        var runner = new EvaluationRunner(); var target = target(c -> new Evidence(Map.of("state", "ok")), new AtomicInteger());
        var before = runner.run(suite(sample("a")), target); var after = runner.run(suite(sample("b")), target);
        assertThrows(IllegalArgumentException.class, () -> ReportComparison.compare(before, after));
        assertThrows(IllegalArgumentException.class, () -> runner.rescore(suite(sample("b")), before));
    }
    @Test void duplicateAndEmptySuitesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> suite(sample("same"), sample("same")));
        assertThrows(IllegalArgumentException.class, () -> suite());
    }
    @Test void htmlEscapesUntrustedEvidence() {
        var report = new EvaluationRunner().run(suite(sample("<script>alert(1)</script>")),
                target(c -> new Evidence(Map.of("state", "</pre><script>alert(2)</script>")), new AtomicInteger()));
        String html = ReportWriter.html(report);
        assertFalse(html.contains("<script>alert")); assertTrue(html.contains("&lt;script&gt;"));
    }
    @Test void negativeControlDetectsAnswerRegression() {
        var suite = DefaultSuite.load();
        var baseline = EvalCli.runner().run(suite, EvalCli.target());
        var candidate = EvalCli.runner().run(suite, new io.seekflux.platform.agenteval.runtime.RuntimeEvaluationTarget(
                "runtime-fixture", "broken", EvalCli::regressionFixture));
        assertTrue(baseline.passed(), () -> EvalJson.pretty(baseline));
        var comparison = ReportComparison.compare(baseline, candidate);
        assertTrue(comparison.regressions().contains("lookup"));
        assertTrue(comparison.regressions().contains("policy-modify"));
        assertTrue(comparison.candidatePassed() < comparison.baselinePassed());
    }
}
