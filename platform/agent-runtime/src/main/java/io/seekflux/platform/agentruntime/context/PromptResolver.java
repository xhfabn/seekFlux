package io.seekflux.platform.agentruntime.context;

public interface PromptResolver {

    String resolve(String promptVersion);
}
