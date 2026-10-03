package io.seekflux.platform.agentruntime.mcp.infrastructure.http;

import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class StreamableHttpMcpClientFactory implements McpClientFactory {

    private final ObjectMapper objectMapper;
    private final McpCredentialProvider credentials;

    public StreamableHttpMcpClientFactory() {
        this(new ObjectMapper(), new EnvironmentMcpCredentialProvider());
    }

    public StreamableHttpMcpClientFactory(McpCredentialProvider credentials) {
        this(new ObjectMapper(), credentials);
    }

    public StreamableHttpMcpClientFactory(
            ObjectMapper objectMapper,
            McpCredentialProvider credentials) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
        this.credentials = credentials;
    }

    @Override
    public McpClient create(McpServerConfig config) {
        if (config.transport() != McpServerConfig.Transport.STREAMABLE_HTTP) {
            throw new McpException("MCP_TRANSPORT_UNSUPPORTED", false);
        }
        if (!McpServerConfig.SUPPORTED_PROTOCOL_VERSION.equals(config.protocolVersion())) {
            throw new McpException("MCP_PROTOCOL_VERSION_UNSUPPORTED", false);
        }
        return new StreamableHttpMcpClient(config, objectMapper, credentials);
    }
}
