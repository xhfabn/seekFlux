package io.seekflux.platform.agenteval;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static io.seekflux.platform.agenteval.Evaluation.*;

/** Sequential, isolated cases; all failures stay in the denominator. */
public final class EvaluationRunner {
    private final List<Grader> graders;
    public EvaluationRunner() { this(List.of(new ChecksGrader())); }
    public EvaluationRunner(List<Grader> graders) {
        this.graders = List.copyOf(graders);
        if (graders.isEmpty()) throw new IllegalArgumentException("at least one grader required");
        graders.forEach(g -> { Evaluation.required(g.id(), "grader id"); Evaluation.required(g.version(), "grader version"); });
        Evaluation.unique(graders.stream().map(Grader::id).toList(), "grader");
    }
    public Report run(Suite suite, EvaluationTarget target) {
        List<CaseResult> results = new ArrayList<>();
        boolean aborted = false;
        for (Case c : suite.cases()) {
            long start = System.nanoTime();
            FaultController faults = new FaultController(c.faults());
            List<Finding> findings = new ArrayList<>();
            Evidence evidence = null;
            var missing = new TreeSet<>(c.requiredCapabilities()); missing.removeAll(target.capabilities());
            if (aborted) findings.add(new Finding("runner", Status.ERROR, "RUN_ABORTED: previous target did not stop"));
            else if (!missing.isEmpty()) findings.add(new Finding("capabilities", Status.ERROR, "unsupported: " + missing));
            else {
                ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
                Future<Graded> future = worker.submit(() -> {
                    Evidence collected;
                    try (var session = target.open(c, faults)) { collected = Objects.requireNonNull(session.execute(), "evidence"); }
                    return new Graded(collected, grade(c, collected));
                });
                try {
                    Graded graded = future.get(c.timeoutMillis(), TimeUnit.MILLISECONDS);
                    evidence = graded.evidence(); findings.addAll(graded.findings());
                }
                catch (TimeoutException e) { findings.add(new Finding("runner", Status.ERROR, "CASE_TIMEOUT")); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); aborted = true;
                    findings.add(new Finding("runner", Status.ERROR, "RUN_INTERRUPTED"));
                }
                catch (ExecutionException e) {
                    findings.add(new Finding("runner", Status.ERROR, e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage()));
                } finally {
                    future.cancel(true); worker.shutdownNow();
                    try {
                        if (!worker.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                            aborted = true; findings.add(new Finding("cleanup", Status.ERROR, "TARGET_DID_NOT_STOP"));
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); aborted = true; }
                }
            }
            faults.hits().forEach((id, count) -> findings.add(new Finding("fault:" + id,
                    count == 1 ? Status.PASS : Status.FAIL, "expected 1 hit, actual " + count)));
            results.add(new CaseResult(c.id(), status(findings), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    findings, evidence, faults.hits()));
        }
        return report(suite, target.id(), target.version(), suite.metadata(), results);
    }
    /** Re-grade stored evidence; execution errors and original fault hits remain authoritative. */
    public Report rescore(Suite suite, Report original) {
        if (!suite.id().equals(original.suiteId()) || !suite.version().equals(original.suiteVersion())
                || !EvalJson.hash(suite).equals(original.suiteHash()))
            throw new IllegalArgumentException("rescore requires the exact source suite; replace graders to change scoring");
        if (!suite.cases().stream().map(Case::id).toList().equals(original.results().stream().map(CaseResult::caseId).toList()))
            throw new IllegalArgumentException("incomplete evidence report");
        List<CaseResult> results = new ArrayList<>();
        boolean gradingAborted = false;
        for (int i = 0; i < suite.cases().size(); i++) {
            var previous = original.results().get(i);
            List<Finding> findings = new ArrayList<>(previous.findings().stream()
                    .filter(f -> !f.id().startsWith("grade:")).toList());
            if (gradingAborted) findings.add(new Finding("grade:runner", Status.ERROR, "RESCORE_ABORTED: grader did not stop"));
            else if (previous.evidence() != null) {
                var c = suite.cases().get(i);
                ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
                Future<List<Finding>> future = worker.submit(() -> grade(c, previous.evidence()));
                try { findings.addAll(future.get(c.timeoutMillis(), TimeUnit.MILLISECONDS)); }
                catch (TimeoutException | ExecutionException e) { findings.add(new Finding("grade:runner", Status.ERROR, e.toString())); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); gradingAborted = true;
                    findings.add(new Finding("grade:runner", Status.ERROR, "RESCORE_INTERRUPTED"));
                } finally {
                    future.cancel(true); worker.shutdownNow();
                    try {
                        if (!worker.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                            gradingAborted = true;
                            findings.add(new Finding("grade:cleanup", Status.ERROR, "GRADER_DID_NOT_STOP"));
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); gradingAborted = true; }
                }
            }
            else if (findings.stream().noneMatch(f -> f.status() == Status.ERROR))
                findings.add(new Finding("evidence", Status.ERROR, "missing execution evidence"));
            results.add(new CaseResult(previous.caseId(), status(findings), previous.tookMillis(),
                    findings, previous.evidence(), previous.faultHits()));
        }
        Map<String, String> metadata = new TreeMap<>(original.metadata());
        metadata.put("rescoredFrom", original.runId());
        return report(suite, original.target(), original.targetVersion(), metadata, results);
    }
    private List<Finding> grade(Case c, Evidence evidence) {
        List<Finding> result = new ArrayList<>();
        for (Grader grader : graders) {
            String prefix = "grade:" + grader.id() + ":";
            try {
                var values = List.copyOf(grader.grade(c, evidence));
                if (values.isEmpty()) throw new IllegalStateException("grader returned no findings");
                Evaluation.unique(values.stream().map(Finding::id).toList(), "finding");
                values.forEach(f -> result.add(new Finding(prefix + f.id(), f.status(), f.detail())));
            } catch (Exception e) { result.add(new Finding(prefix + "error", Status.ERROR, e.toString())); }
        }
        return result;
    }
    private Report report(Suite suite, String target, String version, Map<String, String> metadata, List<CaseResult> results) {
        Map<String, String> manifest = new TreeMap<>(metadata);
        manifest.put("javaVersion", System.getProperty("java.version"));
        manifest.put("os", System.getProperty("os.name"));
        manifest.put("evaluationVersion", "1.0.0-SNAPSHOT");
        return new Report(SCHEMA, UUID.randomUUID().toString(), Instant.now().toString(), suite.id(), suite.version(),
                EvalJson.hash(suite), target, version, manifest,
                graders.stream().map(g -> g.id() + "@" + g.version()).toList(), results);
    }
    private static Status status(List<Finding> findings) {
        if (findings.isEmpty() || findings.stream().anyMatch(f -> f.status() == Status.ERROR)) return Status.ERROR;
        return findings.stream().anyMatch(f -> f.status() == Status.FAIL) ? Status.FAIL : Status.PASS;
    }
    private record Graded(Evidence evidence, List<Finding> findings) {}
}

