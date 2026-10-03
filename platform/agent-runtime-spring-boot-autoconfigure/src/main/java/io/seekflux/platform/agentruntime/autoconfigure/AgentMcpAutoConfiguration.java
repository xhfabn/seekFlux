package io.seekflux.platform.agentruntime.autoconfigure;

import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.connection.McpConnectionManager;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.spi.McpEventRecorder;
import io.seekflux.platform.agentruntime.mcp.infrastructure.schema.McpSchemaTranslator;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolSchemaAdapter;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClientFactory;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Opt-in MCP assembly. Discovery failure removes only that source, not the host's local tools. */
@AutoConfiguration(after = AgentRuntimeAutoConfiguration.class)
@ConditionalOnBean(AgentToolRegistry.class)
@ConditionalOnProperty(prefix = "seekflux.agent.mcp", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentMcpProperties.class)
public class AgentMcpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    McpCredentialProvider seekFluxMcpCredentialProvider() {
        return new EnvironmentMcpCredentialProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    McpClientFactory seekFluxMcpClientFactory(McpCredentialProvider credentials) {
        return new StreamableHttpMcpClientFactory(credentials);
    }

    @Bean
    @ConditionalOnMissingBean
    McpToolSchemaAdapter seekFluxMcpSchemaAdapter() {
        return new McpSchemaTranslator();
    }

    @Bean
    @ConditionalOnMissingBean
    McpToolAuthorizer seekFluxMcpToolAuthorizer() {
        return McpToolAuthorizer.ALLOW;
    }

    @Bean
    @ConditionalOnMissingBean
    McpToolResultAdapter seekFluxMcpToolResultAdapter() {
        return McpToolResultAdapter.DEFAULT;
    }

    @Bean
    @ConditionalOnMissingBean
    McpEventRecorder seekFluxMcpEventRecorder() {
        return McpEventRecorder.NOOP;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    McpConnectionManager seekFluxMcpConnectionManager(
            AgentMcpProperties properties,
            AgentToolRegistry registry,
            McpClientFactory clients,
            McpToolSchemaAdapter schemas,
            McpEventRecorder events,
            ObjectProvider<Clock> clock,
            McpToolAuthorizer authorizer,
            McpToolResultAdapter results) {
        return new McpConnectionManager(properties.runtimeConfigs(), registry,
                clients, schemas, events, clock.getIfAvailable(Clock::systemUTC), authorizer, results);
    }
}
