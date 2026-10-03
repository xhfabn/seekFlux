package io.seekflux.platform.agentruntime.mcp.integration;

import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClientFactory;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpDefaultsTest {

    private final McpToolPolicy policy = new McpToolPolicy("lookup", "v1",
            AgentTool.Effect.READ_ONLY, false, Set.of(), "");
    private final McpServerConfig config = McpServerConfig.streamableHttp("docs",
            URI.create("https://example.test/mcp"), Map.of("lookup", policy));

    @Test
    void defaultCredentialsAreOptionalButConfiguredMissingSecretsFailClosed() {
        EnvironmentMcpCredentialProvider credentials = new EnvironmentMcpCredentialProvider(
                key -> "TOKEN".equals(key) ? "private-token" : null);
        assertThat(credentials.headers("")).isEmpty();
        assertThat(credentials.headers("env:TOKEN"))
                .containsEntry("Authorization", "Bearer private-token");
        assertThatThrownBy(() -> credentials.headers("env:MISSING"))
                .hasMessage("MCP_CREDENTIAL_UNAVAILABLE");
        assertThatThrownBy(() -> credentials.headers("raw:private-token"))
                .hasMessage("MCP_CREDENTIAL_REFERENCE_INVALID");
    }

    @Test
    void unsupportedTransportAndVersionAreRejectedByDefaultFactoryNotConfiguration() {
        McpServerConfig custom = new McpServerConfig(1, "docs", "v1",
                McpServerConfig.Transport.CUSTOM, URI.create("custom://docs"), "", "future-v1",
                config.connectTimeout(), config.requestTimeout(), 1, 1,
                config.circuitOpenDuration(), config.maxResponseBytes(), Map.of());
        assertThatThrownBy(() -> new StreamableHttpMcpClientFactory().create(custom))
                .hasMessage("MCP_TRANSPORT_UNSUPPORTED");
        McpServerConfig future = new McpServerConfig(1, "docs", "v1", config.transport(),
                config.endpoint(), "", "future-v1", config.connectTimeout(), config.requestTimeout(),
                1, 1, config.circuitOpenDuration(), config.maxResponseBytes(), Map.of());
        assertThatThrownBy(() -> new StreamableHttpMcpClientFactory().create(future))
                .hasMessage("MCP_PROTOCOL_VERSION_UNSUPPORTED");
    }

    @Test
    void standardResultsNeedNoReceiptAndUnknownStatusNeverMeansSuccess() {
        McpCallResult standard = new McpCallResult(false, Map.of("structuredContent", Map.of("value", 1)));
        var converted = McpToolResultAdapter.DEFAULT.convert(config, policy, standard);
        assertThat(converted.success()).isTrue();
        assertThat(converted.externalReceipt()).isEmpty();
        assertThat(McpToolResultAdapter.DEFAULT.reconcile(config, policy, standard).resolution())
                .isEqualTo(SideEffectReconciliation.Resolution.UNKNOWN);
        assertThat(McpToolResultAdapter.DEFAULT.convert(config, policy,
                new McpCallResult(true, Map.of())).errorCode()).isEqualTo("MCP_TOOL_REPORTED_ERROR");
    }
}
