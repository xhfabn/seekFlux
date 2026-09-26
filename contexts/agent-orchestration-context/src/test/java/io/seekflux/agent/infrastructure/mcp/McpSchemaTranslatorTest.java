package io.seekflux.agent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpSchemaTranslatorTest {

    private final McpSchemaTranslator translator = new McpSchemaTranslator(new ObjectMapper());

    @Test
    void translatesOnlyTheBoundedLocalSchemaAndFreezesLocalPolicyVersions() {
        McpRemoteTool remote = new McpRemoteTool(
                "find",
                "remote description",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "maxLength", 99_999),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20),
                                "tags", Map.of(
                                        "type", "array",
                                        "maxItems", 1_000,
                                        "items", Map.of("type", "string"))),
                        "required", java.util.List.of("query")));
        McpToolPolicy localPolicy = new McpToolPolicy(
                "find", "policy-v7", AgentTool.Effect.READ_ONLY, true,
                Set.of("tenant-a"), "");

        McpSchemaTranslator.TranslatedTool translated = translator.translate(
                config(localPolicy), remote, localPolicy, null);

        assertThat(translated.schema().version())
                .startsWith("mcp-config-v3-policy-v7-");
        assertThat(translated.schema().parameters().get("query").maxLength()).isEqualTo(4_096);
        assertThat(translated.schema().parameters().get("tags").maxItems()).isEqualTo(64);
        assertThat(translated.schema().parameters().get("limit").type())
                .isEqualTo(AgentToolParameter.Type.INTEGER);
        assertThat(localPolicy.effect()).isEqualTo(AgentTool.Effect.READ_ONLY);
        assertThat(localPolicy.approvalRequired()).isTrue();
    }

    @Test
    void rejectsNestedRemoteObjectsInsteadOfTrustingTheirSchema() {
        McpToolPolicy policy = new McpToolPolicy(
                "write", "policy-v1", AgentTool.Effect.MUTATING, false, Set.of(), "");
        McpRemoteTool remote = new McpRemoteTool(
                "write", "", Map.of(
                        "type", "object",
                        "properties", Map.of("payload", Map.of(
                                "type", "object",
                                "properties", Map.of("value", Map.of("type", "string"))))));

        assertThatThrownBy(() -> translator.translate(config(policy), remote, policy, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported MCP Tool property type");
    }

    @Test
    void rejectsPathologicallyDeepSchemaBeforeCanonicalization() {
        Map<String, Object> nested = Map.of("type", "string");
        for (int depth = 0; depth < 12; depth++) {
            nested = Map.of("nested", nested);
        }
        McpRemoteTool remote = new McpRemoteTool("find", "", nested);

        assertThatThrownBy(() -> translator.schemaHash(remote))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth or node limit");
    }

    private static McpServerConfig config(McpToolPolicy policy) {
        return new McpServerConfig(
                1, "docs", "config-v3", McpServerConfig.Transport.STREAMABLE_HTTP,
                URI.create("http://127.0.0.1:9999/mcp"), "",
                McpServerConfig.SUPPORTED_PROTOCOL_VERSION,
                Duration.ofSeconds(1), Duration.ofSeconds(2),
                2, 3, Duration.ofSeconds(5), 4096,
                Map.of(policy.remoteToolName(), policy));
    }
}
