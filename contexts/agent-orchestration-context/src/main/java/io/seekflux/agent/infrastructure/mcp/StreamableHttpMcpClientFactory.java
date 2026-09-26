package io.seekflux.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class StreamableHttpMcpClientFactory implements McpClientFactory {

    private final ObjectMapper objectMapper;
    private final McpCredentialProvider credentials;

    public StreamableHttpMcpClientFactory(
            ObjectMapper objectMapper,
            McpCredentialProvider credentials) {
        this.objectMapper = objectMapper;
        this.credentials = credentials;
    }

    @Override
    public McpClient create(McpServerConfig config) {
        return new StreamableHttpMcpClient(config, objectMapper, credentials);
    }
}
