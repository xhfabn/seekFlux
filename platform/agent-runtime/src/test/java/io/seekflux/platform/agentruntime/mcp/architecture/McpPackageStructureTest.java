package io.seekflux.platform.agentruntime.mcp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.seekflux.platform.agentruntime.mcp.connection.McpConnectionManager;
import io.seekflux.platform.agentruntime.mcp.exception.McpException;
import io.seekflux.platform.agentruntime.mcp.infrastructure.auth.EnvironmentMcpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClient;
import io.seekflux.platform.agentruntime.mcp.infrastructure.http.StreamableHttpMcpClientFactory;
import io.seekflux.platform.agentruntime.mcp.infrastructure.schema.McpSchemaTranslator;
import io.seekflux.platform.agentruntime.mcp.infrastructure.tool.DefaultMcpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.infrastructure.tool.McpProxyTool;
import io.seekflux.platform.agentruntime.mcp.model.McpCallResult;
import io.seekflux.platform.agentruntime.mcp.model.McpEvent;
import io.seekflux.platform.agentruntime.mcp.model.McpRemoteTool;
import io.seekflux.platform.agentruntime.mcp.model.McpServerConfig;
import io.seekflux.platform.agentruntime.mcp.model.McpToolPolicy;
import io.seekflux.platform.agentruntime.mcp.model.McpTranslatedTool;
import io.seekflux.platform.agentruntime.mcp.spi.McpClient;
import io.seekflux.platform.agentruntime.mcp.spi.McpClientFactory;
import io.seekflux.platform.agentruntime.mcp.spi.McpCredentialProvider;
import io.seekflux.platform.agentruntime.mcp.spi.McpEventRecorder;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolAuthorizer;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolCallGateway;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolResultAdapter;
import io.seekflux.platform.agentruntime.mcp.spi.McpToolSchemaAdapter;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpPackageStructureTest {

    private static final String ROOT = "io.seekflux.platform.agentruntime.mcp.";
    private static final List<Class<?>> MODELS = List.of(McpCallResult.class, McpEvent.class,
            McpRemoteTool.class, McpServerConfig.class, McpToolPolicy.class, McpTranslatedTool.class);
    private static final List<Class<?>> SPIS = List.of(McpClient.class, McpClientFactory.class,
            McpCredentialProvider.class, McpEventRecorder.class, McpToolAuthorizer.class,
            McpToolCallGateway.class, McpToolResultAdapter.class, McpToolSchemaAdapter.class);

    @Test
    void separatesContractsConnectionAndDefaultImplementations() {
        MODELS.forEach(type -> assertThat(type.getPackageName()).isEqualTo(ROOT + "model"));
        SPIS.forEach(type -> assertThat(type.getPackageName()).isEqualTo(ROOT + "spi"));
        assertThat(McpConnectionManager.class.getPackageName()).isEqualTo(ROOT + "connection");
        assertThat(McpException.class.getPackageName()).isEqualTo(ROOT + "exception");
        assertThat(EnvironmentMcpCredentialProvider.class.getPackageName()).isEqualTo(ROOT + "infrastructure.auth");
        assertThat(StreamableHttpMcpClient.class.getPackageName()).isEqualTo(ROOT + "infrastructure.http");
        assertThat(StreamableHttpMcpClientFactory.class.getPackageName()).isEqualTo(ROOT + "infrastructure.http");
        assertThat(McpSchemaTranslator.class.getPackageName()).isEqualTo(ROOT + "infrastructure.schema");
        assertThat(McpProxyTool.class.getPackageName()).isEqualTo(ROOT + "infrastructure.tool");
        assertThat(DefaultMcpToolResultAdapter.class.getPackageName()).isEqualTo(ROOT + "infrastructure.tool");
    }

    @Test
    void contractSignaturesDoNotExposeJacksonOrConcreteDefaultImplementations() {
        for (List<Class<?>> group : List.of(MODELS, SPIS)) {
            for (Class<?> type : group) {
                Arrays.stream(type.getDeclaredFields()).forEach(field -> assertContractType(field.getType()));
                Arrays.stream(type.getDeclaredMethods()).forEach(method -> {
                    assertContractType(method.getReturnType());
                    Arrays.stream(method.getParameterTypes()).forEach(McpPackageStructureTest::assertContractType);
                });
            }
        }
    }

    @Test
    void schemaAndToolProxiesUseSharedModelsAndConnectionContract() throws Exception {
        assertThat(McpToolSchemaAdapter.class.getMethod("translate", McpServerConfig.class,
                McpRemoteTool.class, McpToolPolicy.class, String.class).getReturnType())
                .isEqualTo(McpTranslatedTool.class);
        assertThat(McpToolCallGateway.class.isAssignableFrom(McpConnectionManager.class)).isTrue();
        Arrays.stream(McpProxyTool.class.getConstructors()).forEach(constructor ->
                assertThat(constructor.getParameterTypes())
                        .contains(McpToolCallGateway.class).doesNotContain(McpConnectionManager.class));
    }

    private static void assertContractType(Class<?> type) {
        assertThat(type.getName()).doesNotStartWith("com.fasterxml.jackson.")
                .doesNotContain(".mcp.infrastructure.");
    }
}
