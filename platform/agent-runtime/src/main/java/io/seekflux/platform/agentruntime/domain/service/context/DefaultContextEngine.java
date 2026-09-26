package io.seekflux.platform.agentruntime.domain.service.context;

import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextCompactionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatToolDefinition;
import io.seekflux.platform.agentruntime.application.spi.capability.prompt.PromptResolver;
import io.seekflux.platform.agentruntime.domain.model.context.CompactionSummary;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextCompactionMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.context.ContextLayer;
import io.seekflux.platform.agentruntime.domain.model.context.ContextWindowPolicy;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

public final class DefaultContextEngine implements ContextEngine {

    private static final String SUMMARY_STRATEGY = "deterministic-skeleton-v1";
    private static final int COMPACTION_LOCK_STRIPES = 64;

    private final PromptResolver prompts;
    private final AgentToolRegistry tools;
    private final ContextCompactionStore compactions;
    private final ContextEventRecorder events;
    private final ContextWindowPolicy policy;
    private final Executor asyncExecutor;
    private final Clock clock;
    private final Object[] compactionLocks = compactionLocks();
    private final java.util.Set<String> asyncInFlight = ConcurrentHashMap.newKeySet();

    public DefaultContextEngine() {
        this(promptVersion -> promptVersion, null);
    }

    public DefaultContextEngine(PromptResolver prompts) {
        this(prompts, null);
    }

    public DefaultContextEngine(PromptResolver prompts, AgentToolRegistry tools) {
        this(prompts, tools, ContextCompactionStore.NOOP, ContextEventRecorder.NOOP,
                ContextWindowPolicy.DEFAULT, Runnable::run, Clock.systemUTC());
    }

    public DefaultContextEngine(
            PromptResolver prompts,
            AgentToolRegistry tools,
            ContextCompactionStore compactions,
            ContextEventRecorder events,
            ContextWindowPolicy policy,
            Executor asyncExecutor,
            Clock clock) {
        this.prompts = java.util.Objects.requireNonNull(prompts, "prompt resolver must not be null");
        this.tools = tools;
        this.compactions = compactions == null ? ContextCompactionStore.NOOP : compactions;
        this.events = events == null ? ContextEventRecorder.NOOP : events;
        this.policy = policy == null ? ContextWindowPolicy.DEFAULT : policy;
        this.asyncExecutor = asyncExecutor == null ? Runnable::run : asyncExecutor;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext) {
        return assemble(session, runtimeContext, decisionContext, ContextAssemblyMode.NORMAL);
    }

    @Override
    public AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext,
            ContextAssemblyMode mode) {
        ContextAssemblyMode effectiveMode = mode == null ? ContextAssemblyMode.NORMAL : mode;
        CompactionSummary meta = compactions.latest(session.sessionId()).orElse(null);
        AssembledContext initial = render(
                session, runtimeContext, decisionContext, effectiveMode, meta);
        int target = policy.budget(effectiveMode);
        boolean hardLimitExceeded = initial.estimatedTokens() > policy.maxInputTokens();
        boolean targetExceeded = initial.estimatedTokens() > target;
        if (!targetExceeded) {
            record(ContextEvent.Type.ASSEMBLED_NOOP, decisionContext, initial, "WITHIN_BUDGET");
            return initial;
        }

        if (effectiveMode == ContextAssemblyMode.NORMAL
                && policy.compactionMode() == ContextCompactionMode.NONE
                && !hardLimitExceeded) {
            record(ContextEvent.Type.ASSEMBLED_NOOP, decisionContext, initial, "COMPACTION_DISABLED");
            return initial;
        }

        if (effectiveMode == ContextAssemblyMode.NORMAL
                && policy.compactionMode() == ContextCompactionMode.ASYNC
                && !hardLimitExceeded) {
            scheduleAsync(session, decisionContext, meta, initial);
            return initial;
        }

        String reason = effectiveMode == ContextAssemblyMode.OVERFLOW_FALLBACK
                ? "PROVIDER_OVERFLOW" : hardLimitExceeded ? "HARD_LIMIT" : "TARGET_LIMIT";
        record(ContextEvent.Type.COMPACTION_TRIGGERED, decisionContext, initial, reason);
        if (hardLimitExceeded || effectiveMode == ContextAssemblyMode.OVERFLOW_FALLBACK) {
            record(ContextEvent.Type.SKELETON_FALLBACK, decisionContext, initial, reason);
        }
        CompactionSummary committed = compact(
                session,
                meta,
                target,
                hardLimitExceeded || effectiveMode == ContextAssemblyMode.OVERFLOW_FALLBACK);
        if (committed == null || committed == meta
                || meta != null && committed.inclusiveCutoff() == meta.inclusiveCutoff()) {
            record(ContextEvent.Type.COMPACTION_NOOP,
                    decisionContext, initial, "NO_ELIGIBLE_COMPLETE_TURN");
            record(ContextEvent.Type.COMPACTION_EXHAUSTED,
                    decisionContext, initial, "PROTECTED_CONTEXT_EXCEEDS_BUDGET");
            return initial;
        }
        AssembledContext compacted = render(
                session, runtimeContext, decisionContext, effectiveMode, committed);
        record(ContextEvent.Type.COMPACTION_COMMITTED, decisionContext, compacted, reason);
        if (compacted.estimatedTokens() > target) {
            record(ContextEvent.Type.COMPACTION_EXHAUSTED,
                    decisionContext, compacted, "PROTECTED_CONTEXT_EXCEEDS_BUDGET");
        }
        return compacted;
    }

    @Override
    public int overflowRetryLimit() {
        return policy.overflowRetryLimit();
    }

    private void scheduleAsync(
            AgentSession session,
            AgentDecisionContext decisionContext,
            CompactionSummary meta,
            AssembledContext initial) {
        String sessionId = session.sessionId();
        if (!asyncInFlight.add(sessionId)) {
            record(ContextEvent.Type.COMPACTION_ASYNC_SKIPPED,
                    decisionContext, initial, "SINGLE_FLIGHT");
            return;
        }
        record(ContextEvent.Type.COMPACTION_ASYNC_SCHEDULED,
                decisionContext, initial, "TARGET_LIMIT");
        try {
            asyncExecutor.execute(() -> {
                try {
                    CompactionSummary committed = compact(
                            session, meta, policy.targetInputTokens(), false);
                    if (committed == null || committed == meta
                            || meta != null
                            && committed.inclusiveCutoff() == meta.inclusiveCutoff()) {
                        record(ContextEvent.Type.COMPACTION_NOOP,
                                decisionContext, initial, "ASYNC_NO_ELIGIBLE_COMPLETE_TURN");
                    } else {
                        record(ContextEvent.Type.COMPACTION_COMMITTED,
                                decisionContext, initial, "ASYNC_TARGET_LIMIT");
                    }
                } catch (RuntimeException failed) {
                    record(ContextEvent.Type.COMPACTION_EXHAUSTED,
                            decisionContext, initial, "ASYNC_FAILED");
                } finally {
                    asyncInFlight.remove(sessionId);
                }
            });
        } catch (RuntimeException rejected) {
            asyncInFlight.remove(sessionId);
            record(ContextEvent.Type.COMPACTION_ASYNC_SKIPPED,
                    decisionContext, initial, "EXECUTOR_REJECTED");
        }
    }

    private CompactionSummary compact(
            AgentSession session,
            CompactionSummary meta,
            int targetTokens,
            boolean skeleton) {
        Object lock = compactionLocks[Math.floorMod(
                session.sessionId().hashCode(), compactionLocks.length)];
        synchronized (lock) {
            List<HistoryTurn> turns = historyTurns(session, meta == null ? 0 : meta.inclusiveCutoff());
            int retainedTurns = skeleton ? 1 : Math.min(policy.recentTurns(), turns.size());
            int retainedBudget = Math.max(64, targetTokens * 2 / 3);
            while (retainedTurns > 1
                    && turns.subList(turns.size() - retainedTurns, turns.size()).stream()
                    .mapToInt(HistoryTurn::estimatedTokens)
                    .sum() > retainedBudget) {
                retainedTurns--;
            }
            if (turns.size() <= retainedTurns) {
                return meta;
            }
            int compactedCount = turns.size() - retainedTurns;
            List<HistoryTurn> compactedTurns = turns.subList(0, compactedCount);
            long fromExclusive = meta == null ? 0 : meta.inclusiveCutoff();
            long cutoff = compactedTurns.getLast().lastPosition();
            if (cutoff <= fromExclusive) {
                return meta;
            }
            int summaryBudget = Math.max(32, Math.min(512, targetTokens / 4));
            long deadlineNanos = System.nanoTime() + policy.compactionTimeout().toNanos();
            String summary = skeletonSummary(
                    meta, compactedTurns, summaryBudget, deadlineNanos);
            Instant now = clock.instant();
            String summaryId = UUID.nameUUIDFromBytes(
                    (session.sessionId() + ":" + cutoff + ":" + SUMMARY_STRATEGY)
                            .getBytes(StandardCharsets.UTF_8)).toString();
            CompactionSummary candidate = new CompactionSummary(
                    1,
                    summaryId,
                    session.sessionId(),
                    fromExclusive,
                    cutoff,
                    SUMMARY_STRATEGY,
                    summary,
                    ContextRenderer.estimateUnicodeTokens(summary),
                    now);
            return compactions.append(candidate);
        }
    }

    private AssembledContext render(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext,
            ContextAssemblyMode mode,
            CompactionSummary summary) {
        List<ContextLayer> layers = new ArrayList<>();
        String stablePrompt = prompts.resolve(runtimeContext.definition().promptVersion());
        layers.add(layer(ContextLayer.Type.STABLE_PREFIX, true,
                new ContextMessage("system", stablePrompt)));
        layers.add(layer(ContextLayer.Type.AGENT_INSTRUCTIONS, true,
                new ContextMessage("system", agentInstructions(decisionContext))));
        var capabilities = capabilities(runtimeContext, decisionContext);
        List<String> ephemeralInstructions = capabilities.activeSkills().stream()
                .filter(capabilities.ephemeralSkillIds()::contains)
                .sorted()
                .map(capabilities.visibleSkills()::get)
                .filter(java.util.Objects::nonNull)
                .filter(skill -> skill.type()
                        == io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition.Type.PROMPT)
                .map(skill -> skill.skillId() + "@" + skill.version() + ":\n" + skill.instruction())
                .toList();
        if (!ephemeralInstructions.isEmpty()) {
            layers.add(layer(ContextLayer.Type.EPHEMERAL_SKILL_INSTRUCTIONS, true,
                    new ContextMessage("system", "ephemeral_skills:\n"
                            + String.join("\n", ephemeralInstructions))));
        }
        List<String> persistentInstructions = capabilities.activeSkills().stream()
                .filter(skill -> !capabilities.ephemeralSkillIds().contains(skill))
                .sorted()
                .map(capabilities.visibleSkills()::get)
                .filter(java.util.Objects::nonNull)
                .filter(skill -> skill.type()
                        == io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition.Type.PROMPT)
                .map(skill -> skill.skillId() + "@" + skill.version() + ":\n" + skill.instruction())
                .toList();
        if (!persistentInstructions.isEmpty()) {
            layers.add(layer(ContextLayer.Type.ACTIVE_SKILL_INSTRUCTIONS, true,
                    new ContextMessage("system", "active_skills:\n"
                            + String.join("\n", persistentInstructions))));
        }
        if (!capabilities.lazySkillSummaries().isEmpty()) {
            layers.add(layer(ContextLayer.Type.SKILL_CATALOG, true,
                    new ContextMessage("system", "available_lazy_skills:\n- "
                            + String.join("\n- ", capabilities.lazySkillSummaries()))));
        }
        layers.add(layer(ContextLayer.Type.DYNAMIC_CAPABILITIES, true,
                new ContextMessage("system", dynamicCapabilities(runtimeContext, decisionContext))));
        if (!session.workspaceState().isEmpty()) {
            layers.add(layer(ContextLayer.Type.WORKSPACE_STATE, true,
                    new ContextMessage("system", "workspace_state:" + session.workspaceState())));
        }
        long cutoff = summary == null ? 0 : summary.inclusiveCutoff();
        if (summary != null) {
            layers.add(layer(ContextLayer.Type.COMPACTION_SUMMARY, true,
                    new ContextMessage("system",
                            "compaction_summary[through=" + cutoff + "]:\n" + summary.summary())));
        }
        List<ContextMessage> history = new ArrayList<>();
        for (HistoryTurn turn : historyTurns(session, cutoff)) {
            turn.entries().forEach(entry -> history.add(entry.message()));
        }
        layers.add(new ContextLayer(ContextLayer.Type.HISTORY, false, history));
        List<ContextMessage> recall = new ArrayList<>();
        for (AgentToolObservation observation : decisionContext.observations()) {
            recall.add(new ContextMessage(
                    "tool",
                    observation.toolName() + ":" + observation.result().output()));
        }
        layers.add(new ContextLayer(ContextLayer.Type.CURRENT_RECALL, true, recall));
        ContextRenderer.RenderedContext rendered = ContextRenderer.render(layers);
        return new AssembledContext(
                decisionContext,
                rendered.messages(),
                specId(stablePrompt),
                rendered.estimatedTokens(),
                policy.budget(mode),
                summary != null,
                cutoff,
                mode,
                chatTools(runtimeContext, decisionContext));
    }

    private static List<HistoryTurn> historyTurns(AgentSession session, long afterPosition) {
        List<HistoryTurn> turns = new ArrayList<>();
        List<HistoryEntry> current = new ArrayList<>();
        for (WorkspaceEvent event : session.events()) {
            if (event.position() <= afterPosition) {
                continue;
            }
            HistoryEntry entry = historyEntry(event);
            if (entry == null) {
                continue;
            }
            if (event instanceof WorkspaceEvent.UserMessage && !current.isEmpty()) {
                turns.add(new HistoryTurn(List.copyOf(current)));
                current.clear();
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            turns.add(new HistoryTurn(List.copyOf(current)));
        }
        return List.copyOf(turns);
    }

    private static HistoryEntry historyEntry(WorkspaceEvent event) {
        if (event instanceof WorkspaceEvent.UserMessage message) {
            return new HistoryEntry(event.position(), new ContextMessage(
                    "user", message.text(), message.messageId(), null, null));
        }
        if (event instanceof WorkspaceEvent.AssistantMessage eventMessage) {
            var message = eventMessage.message();
            return new HistoryEntry(event.position(), new ContextMessage(
                    "assistant", assistantContent(message), message.messageId(), null, null));
        }
        if (event instanceof WorkspaceEvent.ToolResultMessage eventMessage) {
            var message = eventMessage.message();
            return new HistoryEntry(event.position(), new ContextMessage(
                    "tool", message.modelContent(), message.messageId(),
                    message.toolCallId(), message.toolName()));
        }
        return null;
    }

    private static String skeletonSummary(
            CompactionSummary previous,
            List<HistoryTurn> turns,
            int tokenBudget,
            long deadlineNanos) {
        StringBuilder value = new StringBuilder("summary_version=1\n");
        if (previous != null) {
            appendFitted(value, "previous=" + previous.summary(), Math.max(16, tokenBudget / 2));
        }
        for (HistoryTurn turn : turns) {
            if (System.nanoTime() >= deadlineNanos) {
                break;
            }
            StringBuilder line = new StringBuilder("turn:");
            turn.entries().stream()
                    .map(HistoryEntry::message)
                    .filter(message -> message.toolName() != null)
                    .forEach(message -> line.append(" tool=")
                            .append(message.toolName()).append('/')
                            .append(message.toolCallId()).append(';'));
            for (HistoryEntry entry : turn.entries()) {
                ContextMessage message = entry.message();
                if (message.toolName() != null) {
                    continue;
                }
                line.append(' ').append(message.role()).append('=');
                line.append(clip(message.content(), 96)).append(';');
            }
            if (!appendFitted(value, line.toString(), tokenBudget)) {
                break;
            }
        }
        return value.toString().trim();
    }

    private static Object[] compactionLocks() {
        Object[] locks = new Object[COMPACTION_LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private static boolean appendFitted(StringBuilder value, String line, int tokenBudget) {
        String fitted = line;
        while (ContextRenderer.estimateUnicodeTokens(value.toString() + fitted + '\n') > tokenBudget) {
            int codePoints = fitted.codePointCount(0, fitted.length());
            if (codePoints <= 16) {
                return false;
            }
            fitted = clip(fitted, Math.max(16, codePoints / 2));
        }
        value.append(fitted).append('\n');
        return true;
    }

    private static String clip(String value, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) {
            return value.replace('\n', ' ');
        }
        int retained = Math.max(1, maxCodePoints - 1);
        int end = value.offsetByCodePoints(0, retained);
        return value.substring(0, end).replace('\n', ' ') + "…";
    }

    private static String assistantContent(
            io.seekflux.platform.agentruntime.domain.model.message.AgentMessage.Assistant message) {
        StringBuilder content = new StringBuilder(message.content() == null ? "" : message.content());
        if (message.reasoningReplayable() && message.reasoning() != null) {
            content.append("\nreasoning:").append(message.reasoning());
        }
        if (!message.toolCalls().isEmpty()) {
            content.append("\ntool_calls:").append(message.toolCalls());
        }
        return content.toString();
    }

    private static ContextLayer layer(
            ContextLayer.Type type, boolean protectedLayer, ContextMessage message) {
        return new ContextLayer(type, protectedLayer, List.of(message));
    }

    private static String agentInstructions(AgentDecisionContext decisionContext) {
        StringBuilder value = new StringBuilder("runtime_context:\n")
                .append("step=").append(decisionContext.step()).append('\n')
                .append("remaining_ms=").append(decisionContext.remaining().toMillis()).append('\n')
                .append("request_attributes=").append(modelVisibleAttributes(
                        decisionContext.request().attributes()));
        return value.toString();
    }

    private String dynamicCapabilities(
            RuntimeContext runtimeContext, AgentDecisionContext decisionContext) {
        StringBuilder value = new StringBuilder("allowed_tools:\n");
        for (String name : effectiveTools(runtimeContext, decisionContext)) {
            value.append("- ").append(name);
            if (tools != null) {
                var schema = tool(name, decisionContext).schema();
                value.append('@').append(schema.version())
                        .append(" parameters=").append(new java.util.TreeMap<>(schema.parameters()));
            }
            value.append('\n');
        }
        return value.toString();
    }

    private static List<String> effectiveTools(
            RuntimeContext runtimeContext, AgentDecisionContext decisionContext) {
        return capabilities(runtimeContext, decisionContext).effectiveTools().stream()
                .sorted().toList();
    }

    private List<ChatToolDefinition> chatTools(
            RuntimeContext runtimeContext, AgentDecisionContext decisionContext) {
        if (tools == null) {
            return List.of();
        }
        return effectiveTools(runtimeContext, decisionContext).stream().map(name -> {
            var schema = tool(name, decisionContext).schema();
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            schema.parameters().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        var parameter = entry.getValue();
                        Map<String, Object> property = new java.util.LinkedHashMap<>();
                        switch (parameter.type()) {
                            case STRING -> property.put("type", "string");
                            case INTEGER -> property.put("type", "integer");
                            case BOOLEAN -> property.put("type", "boolean");
                            case STRING_LIST -> {
                                property.put("type", "array");
                                property.put("items", Map.of("type", "string"));
                            }
                        }
                        if (parameter.maxLength() != null) {
                            if (parameter.type()
                                    == io.seekflux.platform.agentruntime.domain.model.tool
                                            .AgentToolParameter.Type.STRING_LIST) {
                                property.put("items", Map.of(
                                        "type", "string",
                                        "maxLength", parameter.maxLength()));
                            } else {
                                property.put("maxLength", parameter.maxLength());
                            }
                        }
                        if (parameter.maxItems() != null) {
                            property.put("maxItems", parameter.maxItems());
                        }
                        if (parameter.minimum() != null) {
                            property.put("minimum", parameter.minimum());
                        }
                        if (parameter.maximum() != null) {
                            property.put("maximum", parameter.maximum());
                        }
                        properties.put(entry.getKey(), Map.copyOf(property));
                        if (parameter.required()) {
                            required.add(entry.getKey());
                        }
                    });
            Map<String, Object> inputSchema = new java.util.LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", Map.copyOf(properties));
            inputSchema.put("required", List.copyOf(required));
            inputSchema.put("additionalProperties", false);
            return new ChatToolDefinition(
                    name, "SeekFlux Tool schema " + schema.version(), inputSchema);
        }).toList();
    }

    private io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool tool(
            String name,
            AgentDecisionContext decisionContext) {
        String frozen = decisionContext.toolSchemaVersions().get(name);
        return frozen == null ? tools.require(name) : tools.require(name, frozen);
    }

    private static Map<String, Object> modelVisibleAttributes(Map<String, Object> attributes) {
        Map<String, Object> visible = new java.util.LinkedHashMap<>(attributes);
        visible.remove("tenantId");
        visible.remove("userId");
        return Map.copyOf(visible);
    }

    private static io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot capabilities(
            RuntimeContext runtimeContext, AgentDecisionContext decisionContext) {
        return decisionContext.capabilities() == null
                ? runtimeContext.capabilities() : decisionContext.capabilities();
    }

    static int estimateUnicodeTokens(String text) {
        return ContextRenderer.estimateUnicodeTokens(text);
    }

    private void record(
            ContextEvent.Type type,
            AgentDecisionContext decision,
            AssembledContext context,
            String reason) {
        events.record(new ContextEvent(
                type,
                decision.request().sessionId(),
                decision.request().requestId(),
                context.estimatedTokens(),
                context.budgetTokens(),
                reason,
                clock.instant()));
    }

    private static String specId(String stablePrefix) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(stablePrefix.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record HistoryEntry(long position, ContextMessage message) {
    }

    private record HistoryTurn(List<HistoryEntry> entries) {
        private long lastPosition() {
            return entries.getLast().position();
        }

        private int estimatedTokens() {
            return entries.stream()
                    .map(HistoryEntry::message)
                    .mapToInt(message -> ContextRenderer.estimateUnicodeTokens(message.content()) + 4)
                    .sum();
        }
    }
}
