package io.seekflux.platform.agentruntime.context;

import io.seekflux.platform.agentruntime.AgentDecisionContext;
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

    @Override
    public AssembledContext assemble(
            AgentSession session,
            RuntimeContext runtimeContext,
            AgentDecisionContext decisionContext) {
        List<ContextMessage> messages = new ArrayList<>();
        messages.add(new ContextMessage("system", runtimeContext.definition().promptVersion()));
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
