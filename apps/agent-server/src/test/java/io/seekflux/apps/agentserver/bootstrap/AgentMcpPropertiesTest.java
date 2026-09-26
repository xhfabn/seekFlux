package io.seekflux.apps.agentserver.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentMcpPropertiesTest {

    @Test
    void disabledConfigurationPublishesNoServersOrAgentTools() {
        AgentMcpProperties properties = new AgentMcpProperties(false, List.of(server()));

        assertThat(properties.runtimeConfigs()).isEmpty();
        assertThat(properties.configuredToolNames()).isEmpty();
    }

    @Test
    void enabledConfigurationBuildsAnExplicitNamespacedAllowlist() {
        AgentMcpProperties properties = new AgentMcpProperties(true, List.of(server()));

        assertThat(properties.runtimeConfigs()).singleElement().satisfies(config -> {
            assertThat(config.serverId()).isEqualTo("docs");
            assertThat(config.credentialRef()).isEqualTo("env:DOCS_MCP_TOKEN");
            assertThat(config.toolPolicies()).containsOnlyKeys("lookup");
        });
        assertThat(properties.configuredToolNames()).containsExactly("docs__lookup");
    }

    private static AgentMcpProperties.Server server() {
        return new AgentMcpProperties.Server(
                "docs", "config-v1", URI.create("https://mcp.example.test/rpc"),
                "env:DOCS_MCP_TOKEN", Duration.ofSeconds(1), Duration.ofSeconds(2),
                4, 3, Duration.ofSeconds(30), 4096,
                List.of(new AgentMcpProperties.Tool(
                        "lookup", "policy-v2", AgentTool.Effect.READ_ONLY,
                        false, Set.of("tenant-a"), Set.of("user-a"), "")));
    }
}
