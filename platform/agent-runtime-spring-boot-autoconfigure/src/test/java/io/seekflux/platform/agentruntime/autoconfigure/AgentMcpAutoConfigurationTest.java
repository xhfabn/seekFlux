package io.seekflux.platform.agentruntime.autoconfigure;

import io.seekflux.platform.agentruntime.mcp.connection.McpConnectionManager;
import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClientFactory;
import io.seekflux.platform.agentruntime.mcp.infrastructure.schema.McpSchemaTranslator;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.spi.McpEventRecorder;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolSchemaAdapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentMcpAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentMcpAutoConfiguration.class))
            .withBean(AgentToolRegistry.class,
                    () -> new AgentToolRegistry(List.of(), AgentToolRegistrationPolicy.SAFE_ONLY));

    private ApplicationContextRunner configured() {
        return runner.withPropertyValues(
                "seekflux.agent.mcp.enabled=true",
                "seekflux.agent.mcp.servers[0].id=docs",
                "seekflux.agent.mcp.servers[0].endpoint=https://example.test/mcp",
                "seekflux.agent.mcp.servers[0].tools[0].remote-name=lookup",
                "seekflux.agent.mcp.servers[0].tools[0].effect=READ_ONLY");
    }

    @Test
    void disabledByDefaultAndDoesNotRequireNetworkOrCreateProtocolBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpConnectionManager.class);
            assertThat(context).doesNotHaveBean(McpClientFactory.class);
        });
    }

    @Test
    void requiresRegistryButNotPersistenceOrModelPorts() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentMcpAutoConfiguration.class))
                .withPropertyValues("seekflux.agent.mcp.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(McpConnectionManager.class);
                });
        runner.withPropertyValues("seekflux.agent.mcp.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpConnectionManager.class);
            assertThat(context).hasSingleBean(StreamableHttpMcpClientFactory.class);
            assertThat(context).hasSingleBean(EnvironmentMcpCredentialProvider.class);
            assertThat(context.getBean(McpConnectionManager.class).health()).isEmpty();
        });
    }

    @Test
    void bindsConfigurationAndUsesHostClientWhileProvidingDefaultPolicies() {
        configured().withBean(McpClientFactory.class, () -> server -> new FakeClient())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpClientFactory.class);
                    assertThat(context).doesNotHaveBean(StreamableHttpMcpClientFactory.class);
                    assertThat(context.getBean(AgentToolRegistry.class).names())
                            .containsExactly("docs__lookup");
                    assertThat(context.getBean(McpConnectionManager.class).health("docs").status())
                            .isEqualTo(McpConnectionManager.Status.CONNECTED);
                });
    }

    @Test
    void customFactoryCanReceiveAnExplicitTransportAndProtocolVersion() {
        configured().withPropertyValues(
                "seekflux.agent.mcp.servers[0].transport=CUSTOM",
                "seekflux.agent.mcp.servers[0].protocol-version=custom-v1",
                "seekflux.agent.mcp.servers[0].endpoint=custom://docs")
                .withBean(McpClientFactory.class, () -> server -> {
                    assertThat(server.transport()).isEqualTo(McpServerConfig.Transport.CUSTOM);
                    assertThat(server.protocolVersion()).isEqualTo("custom-v1");
                    return new FakeClient();
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentToolRegistry.class).names()).containsExactly("docs__lookup");
                });
    }

    @Test
    void backsOffForEveryHostExtensionAndForHostManager() {
        McpToolAuthorizer authorizer = (server, policy, call) -> false;
        McpToolResultAdapter results = McpToolResultAdapter.DEFAULT;
        McpToolSchemaAdapter schemas = new McpSchemaTranslator();
        McpCredentialProvider credentials = ignored -> Map.of("X-Token", "test");
        McpEventRecorder events = ignored -> { };
        configured().withBean(McpClientFactory.class, () -> server -> new FakeClient())
                .withBean(McpToolAuthorizer.class, () -> authorizer)
                .withBean(McpToolResultAdapter.class, () -> results)
                .withBean(McpToolSchemaAdapter.class, () -> schemas)
                .withBean(McpCredentialProvider.class, () -> credentials)
                .withBean(McpEventRecorder.class, () -> events)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(McpToolAuthorizer.class)).isSameAs(authorizer);
                    assertThat(context.getBean(McpToolResultAdapter.class)).isSameAs(results);
                    assertThat(context.getBean(McpToolSchemaAdapter.class)).isSameAs(schemas);
                    assertThat(context.getBean(McpCredentialProvider.class)).isSameAs(credentials);
                    assertThat(context.getBean(McpEventRecorder.class)).isSameAs(events);
                });
        McpConnectionManager host = new McpConnectionManager(List.of(),
                new AgentToolRegistry(List.of(), AgentToolRegistrationPolicy.SAFE_ONLY));
        runner.withPropertyValues("seekflux.agent.mcp.enabled=true")
                .withBean(McpConnectionManager.class, () -> host).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(McpConnectionManager.class);
                    assertThat(context.getBean(McpConnectionManager.class)).isSameAs(host);
                });
    }

    @Test
    void discoveryFailureLeavesApplicationUsableAndSourceDisconnected() {
        configured().withBean(McpClientFactory.class, () -> server -> {
            throw new McpException("MCP_CONNECTION_FAILED", true);
        }).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AgentToolRegistry.class).names()).isEmpty();
            assertThat(context.getBean(McpConnectionManager.class).health("docs").lastReason())
                    .isEqualTo("MCP_CONNECTION_FAILED");
        });
    }

    @Test
    void rejectsMissingEffectInsteadOfInferringSafetyFromRemoteServer() {
        configured().withPropertyValues("seekflux.agent.mcp.servers[0].tools[0].effect=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void mutatingRemoteToolsStillNeedExplicitHostRegistrationPolicy() {
        configured().withPropertyValues("seekflux.agent.mcp.servers[0].tools[0].effect=MUTATING")
                .withBean(McpClientFactory.class, () -> server -> new FakeClient())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentToolRegistry.class).names()).isEmpty();
                    assertThat(context.getBean(McpConnectionManager.class).health("docs").status())
                            .isEqualTo(McpConnectionManager.Status.DISCONNECTED);
                });
    }

    private static class FakeClient implements McpClient {
        @Override
        public List<McpRemoteTool> initializeAndList(CancellationToken token) {
            return List.of(new McpRemoteTool("lookup", "lookup",
                    Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public McpCallResult callTool(String tool, Map<String, Object> arguments,
                Duration timeout, CancellationToken token) {
            return new McpCallResult(false, Map.of());
        }

        @Override
        public void close() { }
    }
}
