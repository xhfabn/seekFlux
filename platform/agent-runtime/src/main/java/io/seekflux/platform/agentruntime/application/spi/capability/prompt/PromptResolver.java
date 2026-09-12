package io.seekflux.platform.agentruntime.application.spi.capability.prompt;

public interface PromptResolver {

    String resolve(String promptVersion);
}
