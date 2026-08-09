package io.seekflux.platform.agentruntime.context;

import io.seekflux.platform.agentruntime.AgentDecisionContext;
import io.seekflux.platform.agentruntime.AgentToolRegistry;
import io.seekflux.platform.agentruntime.AgentToolObservation;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.WorkspaceEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class DefaultContextEngine implements ContextEngine {

    private final PromptResolver prompts;
    private final AgentToolRegistry tools;

    public DefaultContextEngine() {
        this(promptVersion -> promptVersion, null);
    }

    public DefaultContextEngine(PromptResolver prompts) {
        this(prompts, null);
    }

    public DefaultContextEngine(PromptResolver prompts, AgentToolRegistry tools) {
        this.prompts = java.util.Objects.requireNonNull(prompts, "prompt resolver must not be null");
        this.tools = tools;
    }

    @Override
    public AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext) {
        List<ContextMessage> messages = new ArrayList<>();
        messages.add(new ContextMessage(
                "system",
                prompts.resolve(runtimeContext.definition().promptVersion())));
        messages.add(new ContextMessage("system", runtimeInstructions(runtimeContext, decisionContext)));
        if (!session.workspaceState().isEmpty()) {
            messages.add(new ContextMessage("system", "workspace_state:" + session.workspaceState()));
        }
        for (WorkspaceEvent event : session.events()) {
            if (event instanceof WorkspaceEvent.UserMessage message) {
                messages.add(new ContextMessage("user", message.text()));
            }
        }
        for (AgentToolObservation observation : decisionContext.observations()) {
            messages.add(new ContextMessage(
                    "tool",
                    observation.toolName() + ":" + observation.result().output()));
        }
        int estimatedTokens = messages.stream()
                .mapToInt(message -> estimateUnicodeTokens(message.content()) + 4)
                .sum();
        return new AssembledContext(
                decisionContext,
                messages,
                specId(messages.getFirst().content()),
                estimatedTokens);
    }

    private String runtimeInstructions(
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext) {
        StringBuilder value = new StringBuilder("runtime_context:\n")
                .append("step=").append(decisionContext.step()).append('\n')
                .append("remaining_ms=").append(decisionContext.remaining().toMillis()).append('\n')
                .append("request_attributes=").append(decisionContext.request().attributes()).append('\n')
                .append("allowed_tools:\n");
        for (String name : effectiveTools(runtimeContext)) {
            value.append("- ").append(name);
            if (tools != null) {
                var schema = tools.require(name).schema();
                value.append('@').append(schema.version())
                        .append(" parameters=").append(new java.util.TreeMap<>(schema.parameters()));
            }
            value.append('\n');
        }
        return value.toString();
    }

    private static List<String> effectiveTools(RuntimeContext runtimeContext) {
        Object configured = runtimeContext.request().attributes().get("allowedTools");
        if (configured instanceof List<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .sorted()
                    .toList();
        }
        return runtimeContext.definition().allowedTools().stream().sorted().toList();
    }

    private static int estimateUnicodeTokens(String text) {
        double estimate = text.codePoints().mapToDouble(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                return 0.1;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return 1.1;
            }
            return codePoint < 128 ? 0.25 : 0.6;
        }).sum();
        return Math.max(1, (int) Math.ceil(estimate));
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
}
