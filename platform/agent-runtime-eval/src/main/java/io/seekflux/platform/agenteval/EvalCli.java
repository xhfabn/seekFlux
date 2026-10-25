package io.seekflux.platform.agenteval;

import io.seekflux.platform.agenteval.runtime.*;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import java.nio.file.*;
import java.util.*;

/** Thin CLI around the same SDK used by JUnit and external consumers. */
public final class EvalCli {
    private EvalCli() {}
    public static void main(String[] args) {
        int code;
        try { code = execute(args); }
        catch (Exception e) { System.err.println("Evaluation error: " + e.getMessage()); code = 2; }
        if (code != 0) System.exit(code);
    }
    public static EvaluationRunner runner() {
        return new EvaluationRunner(List.of(new ChecksGrader(), new RuntimeInvariantsGrader()));
    }
    public static RuntimeEvaluationTarget target() {
        return new RuntimeEvaluationTarget("runtime-fixture", "generic-fixtures-v1", ScriptedFixture::new);
    }
    public static int execute(String... args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("demo <directory> | run <suite.json> <directory> | compare <baseline.json> <candidate.json> | rescore <suite.json> <report.json> <directory>");
        return switch (args[0]) {
            case "demo" -> {
                require(args, 2);
                Path output = Path.of(args[1]); var suite = DefaultSuite.load();
                var baseline = runner().run(suite, target());
                var candidate = runner().run(suite, new RuntimeEvaluationTarget("runtime-fixture",
                        "intentional-answer-regression-v1", EvalCli::regressionFixture));
                ReportWriter.write(output.resolve("baseline"), baseline);
                ReportWriter.write(output.resolve("candidate"), candidate);
                EvalJson.write(output.resolve("suite.json"), suite);
                var comparison = ReportComparison.compare(baseline, candidate);
                EvalJson.write(output.resolve("comparison.json"), comparison);
                System.out.println("Baseline: " + baseline.count(Evaluation.Status.PASS) + "/" + suite.cases().size());
                System.out.println("Intentional regression detected: " + comparison.regressions());
                System.out.println("Report: " + output.resolve("baseline/index.html").toAbsolutePath());
                yield baseline.passed() && !comparison.regressions().isEmpty() ? 0 : 1;
            }
            case "run" -> {
                require(args, 3);
                var report = runner().run(EvalJson.read(Path.of(args[1]), Evaluation.Suite.class), target());
                ReportWriter.write(Path.of(args[2]), report);
                System.out.println("PASS=" + report.count(Evaluation.Status.PASS) + " FAIL=" + report.count(Evaluation.Status.FAIL)
                        + " ERROR=" + report.count(Evaluation.Status.ERROR));
                yield report.passed() ? 0 : 1;
            }
            case "compare" -> {
                require(args, 3);
                var comparison = ReportComparison.compare(EvalJson.read(Path.of(args[1]), Evaluation.Report.class),
                        EvalJson.read(Path.of(args[2]), Evaluation.Report.class));
                System.out.println(EvalJson.pretty(comparison));
                yield comparison.regressions().isEmpty() ? 0 : 1;
            }
            case "rescore" -> {
                require(args, 4);
                var report = runner().rescore(EvalJson.read(Path.of(args[1]), Evaluation.Suite.class),
                        EvalJson.read(Path.of(args[2]), Evaluation.Report.class));
                ReportWriter.write(Path.of(args[3]), report);
                yield report.passed() ? 0 : 1;
            }
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        };
    }
    /** Negative control changes answers only; the suite and expected values remain identical. */
    static RuntimeFixture regressionFixture(Evaluation.Case c) {
        var delegate = new ScriptedFixture(c);
        return new RuntimeFixture() {
            public Collection<? extends AgentTool> tools() { return delegate.tools(); }
            public AgentDefinition definition() { return delegate.definition(); }
            public ToolExecutionPolicy policy() { return delegate.policy(); }
            public Map<String, Object> effects() { return delegate.effects(); }
            public AgentPlanner planner() {
                var original = delegate.planner();
                return context -> {
                    var decision = original.decide(context);
                    if (decision instanceof AgentDecision.Complete complete && complete.output().containsKey("value")) {
                        Map<String, Object> broken = new HashMap<>(complete.output()); broken.put("value", "WRONG");
                        return new AgentDecision.Complete(broken);
                    }
                    return decision;
                };
            }
        };
    }
    private static void require(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("invalid arguments for " + args[0]);
    }
}
