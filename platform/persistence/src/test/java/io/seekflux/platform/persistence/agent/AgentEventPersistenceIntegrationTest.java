package io.seekflux.platform.persistence.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.*;
import io.seekflux.platform.agentruntime.domain.model.run.*;
import io.seekflux.platform.agentruntime.domain.model.session.*;
import io.seekflux.platform.agentruntime.domain.model.tool.*;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import io.seekflux.platform.agentruntime.domain.service.recovery.*;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.domain.service.tool.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Opt-in, real PostgreSQL transactions. Use an isolated database, never a business database. */
@EnabledIfSystemProperty(named = "seekflux.test.jdbc-url", matches = ".+")
class AgentEventPersistenceIntegrationTest {
    private static JdbcClient jdbc;
    private static DataSourceTransactionManager transactions;
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private JdbcAgentSessionStore sessions;
    private JdbcAgentRecoveryStore recovery;
    private ExecutorService executor;
    private AgentRunRequest request;
    private AgentDefinition definition;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    @BeforeAll static void database() {
        var source = new DriverManagerDataSource(System.getProperty("seekflux.test.jdbc-url"),
                System.getProperty("seekflux.test.jdbc-user", System.getProperty("user.name")), "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(source);
        transactions = new DataSourceTransactionManager(source);
    }

    @BeforeEach void session() {
        sessions = transactional(new JdbcAgentSessionStore(jdbc, MAPPER, 10));
        recovery = transactional(new JdbcAgentRecoveryStore(jdbc, MAPPER, sessions));
        executor = Executors.newFixedThreadPool(4);
        String id = UUID.randomUUID().toString();
        request = new AgentRunRequest("request-" + id, "session-" + id, "turn-" + id, "input", Map.of());
        definition = new AgentDefinition("agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("search"), 20, 19, Duration.ofSeconds(30), true);
        sessions.createIfAbsent(request.sessionId(), definition, Instant.now());
        sessions.commitIngress(request, 1, Instant.now());
    }

    @AfterEach void stop() { executor.shutdownNow(); }

    @SuppressWarnings("unchecked") private static <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private AgentRuntime runtime() {
        return runtime(null);
    }

    private AgentRuntime runtime(java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        AgentTool tool = new AgentTool() {
            public String name() { return "search"; }
            public String description() { return "Search actual records"; }
            public AgentToolSchema schema() { return new AgentToolSchema("search-v1",
                    Map.of("query", AgentToolParameter.requiredString(100).withDescription("Literal user query"))); }
            public Effect effect() { return Effect.READ_ONLY; }
            public AgentToolResult execute(AgentToolContext context) {
                toolCalls.incrementAndGet();
                if (action != null) return action.apply(context);
                return AgentToolResult.success(Map.of("answer", "large-result-" + "x".repeat(8192)), "trace");
            }
        };
        var registry = new AgentToolRegistry(List.of(tool));
        return new AgentRuntime(registry, new DefaultAgentToolExecutor(registry), executor, AgentRunRecorder.NOOP, Clock.systemUTC());
    }

    private AgentPlanner planner(int toolTurns) {
        return context -> {
            modelCalls.incrementAndGet();
            if (!context.observations().isEmpty()) assertEquals(context.observations().size() * 2, context.messages().size());
            return context.step() <= toolTurns
                    ? new AgentDecision.CallTool("search", Map.of("query", "q" + context.step()))
                    : new AgentDecision.Complete(Map.of("answer", "done"));
        };
    }

    private AgentRecoveryExecution execution(RecoveryPlan plan, RecoveryFaultInjector fault) {
        return new AgentRecoveryExecution(recovery, plan, 1,
                sessions.restoreFresh(request.sessionId()).orElseThrow().position(), Map.of(), Clock.systemUTC(), fault);
    }

    private RecoveryPlan resume() {
        return recovery.commitResume(new ResumeIngress(1, request.sessionId(), request.requestId(),
                request.turnId(), ResumeSource.CRASH_RECOVERY), 1, Instant.now());
    }

    private RecoveryFaultInjector crash(RecoveryPoint point) {
        return actual -> { if (actual == point) throw new SimulatedCrash(); };
    }

    private long count(String type) {
        return jdbc.sql("SELECT count(*) FROM agent.workspace_events WHERE session_id = :session AND event_type = :type")
                .param("session", request.sessionId()).param("type", type).query(Long.class).single();
    }

    @Test void persistsAssistantBeforeToolDispatchAndRecoversWithoutRepeatingModel() {
        var runtime = runtime();
        assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT))));
        assertEquals(1, count("ASSISTANT_MESSAGE"));
        assertEquals(0, toolCalls.get());
        assertEquals(AgentSessionStatus.EXECUTING, sessions.restoreFresh(request.sessionId()).orElseThrow().status());
        var plan = resume();
        var frozen = plan.checkpoint().definition().toolDefinitions().get("search");
        assertEquals("Search actual records", frozen.description());
        assertEquals("Literal user query", frozen.schema().parameters().get("query").description());
        var result = runtime.run(definition, request, planner(1), new CancellationToken(), execution(plan, RecoveryFaultInjector.NONE));
        assertEquals(2, modelCalls.get());
        assertEquals(1, toolCalls.get());
        assertEquals(3, result.messages().size());
    }

    @Test void resultFactAndJournalSurviveCrashBeforePostTurnWithoutRepeatingTool() {
        var runtime = runtime();
        assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_TOOL_RESULT_COMMIT))));
        assertEquals(1, count("TOOL_RESULT_MESSAGE"));
        var plan = resume();
        assertEquals(ToolJournalStatus.SUCCEEDED, plan.toolCalls().getFirst().status());
        runtime.run(definition, request, planner(1), new CancellationToken(), execution(plan, RecoveryFaultInjector.NONE));
        assertEquals(1, toolCalls.get());
        assertEquals(1, count("TOOL_RESULT_MESSAGE"));
    }

    @Test void terminalCursorIsSmallAndOutcomeCommitIsIdempotent() {
        var result = runtime().run(definition, request, planner(3), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, RecoveryFaultInjector.NONE));
        String checkpoint = jdbc.sql("SELECT payload::text FROM agent.runtime_checkpoints WHERE session_id = :session")
                .param("session", request.sessionId()).query(String.class).single();
        assertTrue(checkpoint.length() < 256, checkpoint);
        assertFalse(checkpoint.contains("messages"));
        assertEquals(4, count("ASSISTANT_MESSAGE"));
        assertEquals(3, count("TOOL_RESULT_MESSAGE"));
        var restored = resume().checkpoint().terminalResult();
        assertEquals(result.messages(), restored.messages());
        assertEquals(result.trace(), restored.trace());
        sessions.appendOutcome(request.sessionId(), restored, 1, Instant.now());
        sessions.appendOutcome(request.sessionId(), restored, 1, Instant.now());
        assertEquals(1, count("RUN_COMPLETED"));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM agent.runtime_checkpoints WHERE session_id = :session")
                .param("session", request.sessionId()).query(Integer.class).single());
        assertTrue(jdbc.sql("SELECT count(*) FROM agent.session_snapshots WHERE session_id = :session")
                .param("session", request.sessionId()).query(Integer.class).single() >= 1);
    }

    @Test void queuesRestoreFromSnapshotReferencesAndArePromotedOnce() {
        var queued = new AgentRunRequest("queued-" + request.requestId(), request.sessionId(), "queued-turn", "later", Map.of());
        sessions.enqueue(queued, Map.of("goal", "later"), 10, Instant.now());
        sessions.saveSnapshotIfNeeded(request.sessionId(), true);
        String snapshot = jdbc.sql("SELECT payload::text FROM agent.session_snapshots WHERE session_id = :session ORDER BY event_position DESC LIMIT 1")
                .param("session", request.sessionId()).query(String.class).single();
        assertFalse(snapshot.contains("later"));
        var fresh = sessions.restoreFresh(request.sessionId()).orElseThrow();
        assertEquals(List.of("later"), fresh.queuedMessages().stream().map(event -> event.request().input()).toList());
        assertEquals(1, sessions.promoteQueued(request.sessionId(), 1, Instant.now()).orElseThrow().messages().size());
        assertTrue(sessions.promoteQueued(request.sessionId(), 1, Instant.now()).isEmpty());
        fresh = sessions.restoreFresh(request.sessionId()).orElseThrow();
        assertTrue(fresh.queuedMessages().isEmpty());
        assertEquals(queued.requestId(), fresh.promotedQueuedExecution().orElseThrow().request().requestId());
        sessions.saveSnapshotIfNeeded(request.sessionId(), true);
        assertEquals(queued.requestId(), sessions.restoreFresh(request.sessionId()).orElseThrow()
                .promotedQueuedExecution().orElseThrow().request().requestId());
    }

    @Test void journalPayloadOnlyReferencesMessageBodies() {
        var runtime = runtime();
        assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_TOOL_RESULT_COMMIT))));
        String journal = jdbc.sql("SELECT payload::text FROM agent.tool_call_journal WHERE session_id = :session")
                .param("session", request.sessionId()).query(String.class).single();
        assertTrue(journal.contains("assistantMessageId"));
        assertTrue(journal.contains("observationToolCallId"));
        assertFalse(journal.contains("large-result"));
    }

    @Test void staleOwnerCannotAppendFactsAndContentConflictsRollback() {
        var runtime = runtime();
        assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT))));
        RuntimeCheckpoint checkpoint = resume().checkpoint();
        long before = sessions.restoreFresh(request.sessionId()).orElseThrow().position();
        assertThrows(io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException.class,
                () -> recovery.saveCheckpoint(checkpoint, 0, Instant.now()));
        assertEquals(before, sessions.restoreFresh(request.sessionId()).orElseThrow().position());
    }

    @Test void immutableMessageConflictAndFailedJournalTransitionRollBackAllAppends() {
        var runtime = runtime();
        assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT))));
        var plan = resume();
        var call = plan.toolCalls().getFirst();
        var assistant = call.assistantMessage();
        var altered = new AgentMessage.Assistant(assistant.schemaVersion(), assistant.messageId(), assistant.requestId(),
                assistant.turnId(), assistant.agentRunId(), assistant.step(), "different immutable body", null, false, assistant.toolCalls());
        var conflicting = new ToolCallJournalEntry(call.schemaVersion(), call.sessionId(), call.requestId(), call.turnId(),
                call.attemptId(), call.step(), call.callIndex(), call.toolCallId(), call.toolName(), call.toolSchemaVersion(),
                call.effect(), call.status(), call.arguments(), call.argumentsDigest(), call.argumentsRepaired(), altered, null, Instant.now());
        long before = sessions.restoreFresh(request.sessionId()).orElseThrow().position();
        assertThrows(IllegalStateException.class, () -> recovery.recordToolDecision(plan.checkpoint(), List.of(conflicting), 1, Instant.now()));
        assertEquals(before, sessions.restoreFresh(request.sessionId()).orElseThrow().position());
        var observation = new AgentToolObservation(call.toolCallId(), call.toolName(), call.toolSchemaVersion(), call.arguments(),
                false, AgentToolResult.success(Map.of("answer", "should-roll-back"), null), 1);
        var wrongRequest = new ToolCallJournalEntry(call.schemaVersion(), call.sessionId(), "missing-request", call.turnId(),
                call.attemptId(), call.step(), call.callIndex(), call.toolCallId(), call.toolName(), call.toolSchemaVersion(),
                call.effect(), ToolJournalStatus.SUCCEEDED, call.arguments(), call.argumentsDigest(), false, assistant, observation, Instant.now());
        assertThrows(IllegalStateException.class, () -> recovery.recordToolResult(wrongRequest, 1, Instant.now()));
        assertEquals(0, count("TOOL_RESULT_MESSAGE"));
        assertEquals(before, sessions.restoreFresh(request.sessionId()).orElseThrow().position());
    }

    @Test void asyncWaitSurvivesSnapshotAndCallbackDoesNotRedispatchTool() {
        var runtime = runtime(context -> AgentToolResult.waiting(
                new io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest.AsyncTask("task", "callback", Duration.ofMinutes(1))));
        var waiting = runtime.run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, RecoveryFaultInjector.NONE));
        assertEquals(AgentTerminalState.WAITING, waiting.state());
        sessions.appendOutcome(request.sessionId(), waiting, 1, Instant.now());
        assertEquals(waiting.waitState(), sessions.restoreFresh(request.sessionId()).orElseThrow().pendingWait().orElseThrow());
        var resolution = new io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution(1, "resolution-" + UUID.randomUUID(),
                waiting.waitState().waitId(), request.sessionId(), request.requestId(), request.turnId(),
                io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution.Outcome.COMPLETED,
                Map.of("answer", "callback-result"), null, Instant.now());
        assertEquals(io.seekflux.platform.agentruntime.domain.model.wait.WaitResolutionResult.Status.COMMITTED,
                recovery.resolveWait(resolution, 1, Instant.now()).status());
        var result = runtime.run(definition, request, planner(1), new CancellationToken(), execution(resume(), RecoveryFaultInjector.NONE));
        assertEquals(1, toolCalls.get());
        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        sessions.appendOutcome(request.sessionId(), result, 1, Instant.now());
        assertTrue(sessions.restoreFresh(request.sessionId()).orElseThrow().pendingWait().isEmpty());
        assertEquals(waiting.waitState(), sessions.waitState(request.sessionId(), waiting.waitState().waitId()).orElseThrow());
    }

    @Test void compactionSwitchesReferencesWithoutDeletingOriginalMessages() {
        var result = runtime().run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, RecoveryFaultInjector.NONE));
        sessions.appendOutcome(request.sessionId(), result, 1, Instant.now());
        long cutoff = jdbc.sql("SELECT max(event_position) FROM agent.workspace_events WHERE session_id = :session AND event_type = 'ASSISTANT_MESSAGE'")
                .param("session", request.sessionId()).query(Long.class).single();
        var summary = new io.seekflux.platform.agentruntime.domain.model.context.CompactionSummary(1,
                UUID.randomUUID().toString(), request.sessionId(), 0, cutoff, "test-v1", "prior turn summary", 8, Instant.now());
        transactional(new JdbcContextCompactionStore(jdbc)).append(summary);
        sessions.saveSnapshotIfNeeded(request.sessionId(), true);
        var fresh = sessions.restoreFresh(request.sessionId()).orElseThrow();
        assertTrue(fresh.events().stream().noneMatch(WorkspaceEvent.AssistantMessage.class::isInstance));
        assertEquals(2, count("ASSISTANT_MESSAGE"));
        assertEquals(1, count("COMPACTION_COMMITTED"));
    }

    @Test void partiallyCompletedBatchKeepsFirstResultAcrossCrash() throws Exception {
        CountDownLatch slow = new CountDownLatch(1);
        AtomicInteger fastCalls = new AtomicInteger();
        var runtime = runtime(context -> {
            if ("fast".equals(context.arguments().get("query"))) fastCalls.incrementAndGet();
            else {
                try { if (!slow.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out"); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            }
            return AgentToolResult.success(Map.of("answer", "ok"), null);
        });
        AgentPlanner planner = context -> context.step() == 1
                ? new AgentDecision.CallTools(List.of(new AgentDecision.ToolCall("search", Map.of("query", "fast")),
                        new AgentDecision.ToolCall("search", Map.of("query", "slow"))))
                : new AgentDecision.Complete(Map.of("answer", "done"));
        try {
            assertThrows(SimulatedCrash.class, () -> runtime.run(definition, request, planner, new CancellationToken(),
                    execution(RecoveryPlan.START_NEW, crash(RecoveryPoint.AFTER_TOOL_RESULT_COMMIT))));
            assertEquals(1, count("TOOL_RESULT_MESSAGE"));
            var plan = resume();
            assertEquals(ToolJournalStatus.SUCCEEDED, plan.toolCalls().getFirst().status());
            slow.countDown();
            var result = runtime.run(definition, request, planner, new CancellationToken(), execution(plan, RecoveryFaultInjector.NONE));
            assertEquals(AgentTerminalState.RESULTS_READY, result.state());
            assertEquals(1, fastCalls.get());
            assertEquals(2, count("TOOL_RESULT_MESSAGE"));
        } finally { slow.countDown(); }
    }

    @Test void legacySelfContainedCheckpointRemainsReadable() {
        var result = runtime().run(definition, request, planner(1), new CancellationToken(),
                execution(RecoveryPlan.START_NEW, RecoveryFaultInjector.NONE));
        var checkpoint = resume().checkpoint();
        jdbc.sql("UPDATE agent.runtime_checkpoints SET schema_version = 1, payload = CAST(:payload AS jsonb) WHERE session_id = :session")
                .param("payload", new JdbcWorkspaceFacts(jdbc, MAPPER).json(new AgentRecoveryCodec(MAPPER).encodeCheckpoint(checkpoint)))
                .param("session", request.sessionId()).update();
        assertEquals(result.messages(), resume().checkpoint().terminalResult().messages());
    }

    private static class SimulatedCrash extends RuntimeException { }
}
