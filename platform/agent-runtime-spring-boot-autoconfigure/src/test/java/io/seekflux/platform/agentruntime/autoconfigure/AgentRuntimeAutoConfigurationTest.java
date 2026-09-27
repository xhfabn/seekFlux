package io.seekflux.platform.agentruntime.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.application.spi.capability.prompt.PromptResolver;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.context.DefaultContextEngine;
import io.seekflux.platform.agentruntime.domain.service.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentRuntimeAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
            .withBean(PromptResolver.class, () -> promptVersion -> promptVersion)
            .withBean(AgentSessionStore.class, () -> mock(AgentSessionStore.class))
            .withBean(ExecutionAuthorityStore.class, () -> mock(ExecutionAuthorityStore.class));

    @Test
    void assemblesRuntimeFromHostProvidedPorts() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(DefaultContextEngine.class);
            assertThat(context).hasSingleBean(SessionExecutor.class);
            assertThat(context).hasSingleBean(Router.class);
            assertThat(context).hasSingleBean(AgentToolRegistry.class);
            assertThat(context.getBean(AgentToolRegistry.class).names()).isEmpty();
        });
    }

    @Test
    void collectsHostProvidedReadOnlyTools() {
        contextRunner.withBean(AgentTool.class, ReadOnlyTool::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AgentToolRegistry.class).names())
                    .containsExactly("echo");
        });
    }

    @Test
    void rejectsMutatingToolsUnlessHostProvidesAnExplicitRegistrationPolicy() {
        contextRunner.withBean(AgentTool.class, MutatingTool::new).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("agent Tool registration denied: mutate [MUTATING]");
        });
    }

    @Test
    void backsOffCompletelyWhenDisabled() {
        contextRunner.withPropertyValues("seekflux.agent.runtime.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AgentRuntime.class);
                    assertThat(context).doesNotHaveBean(Router.class);
                });
    }

    @Test
    void failsFastWhenRequiredHostPortsAreMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentRuntimeAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure().getMessage())
                            .contains("PromptResolver");
                });
    }

    private static class ReadOnlyTool implements AgentTool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public AgentToolSchema schema() {
            return new AgentToolSchema("v1", Map.of());
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public AgentToolResult execute(AgentToolContext context) {
            return AgentToolResult.success(Map.of("value", "ok"), null);
        }
    }

    private static final class MutatingTool extends ReadOnlyTool {
        @Override
        public String name() {
            return "mutate";
        }

        @Override
        public AgentToolSchema schema() {
            return new AgentToolSchema("v1", Map.of());
        }

        @Override
        public Effect effect() {
            return Effect.MUTATING;
        }
    }
}
