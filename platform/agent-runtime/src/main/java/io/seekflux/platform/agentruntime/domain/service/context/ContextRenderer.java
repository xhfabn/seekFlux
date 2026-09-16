package io.seekflux.platform.agentruntime.domain.service.context;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import io.seekflux.platform.agentruntime.domain.model.context.ContextLayer;
import java.util.List;

/** Stateless renderer and complete-message token estimator. */
public final class ContextRenderer {

    private ContextRenderer() {
    }

    public static RenderedContext render(List<ContextLayer> layers) {
        List<ContextMessage> messages = layers.stream()
                .flatMap(layer -> layer.messages().stream())
                .toList();
        return new RenderedContext(messages, estimate(messages));
    }

    public static int estimate(List<ContextMessage> messages) {
        return messages.stream()
                .mapToInt(message -> estimateUnicodeTokens(message.content()) + 4)
                .sum();
    }

    public static int estimateUnicodeTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
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

    public record RenderedContext(List<ContextMessage> messages, int estimatedTokens) {

        public RenderedContext {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
