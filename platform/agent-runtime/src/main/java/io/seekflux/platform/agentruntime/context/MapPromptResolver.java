package io.seekflux.platform.agentruntime.context;

import java.util.Map;

public final class MapPromptResolver implements PromptResolver {

    private final Map<String, String> prompts;

    public MapPromptResolver(Map<String, String> prompts) {
        this.prompts = Map.copyOf(prompts);
    }

    @Override
    public String resolve(String promptVersion) {
        String prompt = prompts.get(promptVersion);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("unknown prompt version: " + promptVersion);
        }
        return prompt;
    }
}
