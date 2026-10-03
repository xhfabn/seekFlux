package io.seekflux.platform.agentruntime.domain.service.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolRegistryTest {

    @Test
    void rejectsDescriptionAndParameterChangesWithoutVersionBumpAtomically() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        var original = new DescribedTool("v1", "original", "original query");
        registry.replaceSource("mcp:docs", List.of(original));
        for (var changed : List.of(new DescribedTool("v1", "different", "original query"),
                new DescribedTool("v1", "original", "different query"))) {
            assertThatThrownBy(() -> registry.replaceSource("mcp:docs", List.of(changed)))
                    .hasMessageContaining("without a version change");
            assertThat(registry.require("docs__read")).isSameAs(original);
        }
        registry.replaceSource("mcp:docs", List.of(new DescribedTool("v2", "different", "different query")));
        assertThat(registry.definitionFor("docs__read", "v1").description()).isEqualTo("original");
        assertThat(registry.definitionFor("docs__read", null).description()).isEqualTo("different");
    }

    @Test
    void rejectsOversizedToolAndParameterDescriptions() {
        assertThatThrownBy(() -> new AgentToolRegistry(List.of(new DescribedTool("v1", "x".repeat(2049), "query"))))
                .hasMessageContaining("description exceeds 2048");
        assertThatThrownBy(() -> AgentToolParameter.requiredString(10).withDescription("x".repeat(2049)))
                .hasMessageContaining("description exceeds 2048");
    }

    private record DescribedTool(String version, String description, String queryDescription) implements AgentTool {
        @Override public String name() { return "docs__read"; }
        @Override public String source() { return "mcp:docs"; }
        @Override public Effect effect() { return Effect.READ_ONLY; }
        @Override public AgentToolSchema schema() {
            return new AgentToolSchema(version, Map.of("query", AgentToolParameter.requiredString(100).withDescription(queryDescription)));
        }
        @Override public AgentToolResult execute(AgentToolContext context) { throw new AssertionError("no execution"); }
    }

    @Test
    void atomicallyReplacesOneSourceAndKeepsFrozenVersionsAddressable() {
        TestTool local = new TestTool("local_search", "local-v1", AgentTool.LOCAL_SOURCE);
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(local), AgentToolRegistrationPolicy.ALLOW_MUTATING);
        TestTool first = new TestTool("docs__read", "remote-v1", "mcp:docs");
        TestTool second = new TestTool("docs__read", "remote-v2", "mcp:docs");

        registry.replaceSource("mcp:docs", List.of(first));
        registry.replaceSource("mcp:docs", List.of(second));

        assertThat(registry.names()).containsExactlyInAnyOrder("local_search", "docs__read");
        assertThat(registry.require("docs__read")).isSameAs(second);
        assertThat(registry.require("docs__read", "remote-v1")).isSameAs(first);
        assertThat(registry.unregisterSource("mcp:docs")).containsExactly("docs__read");
        assertThat(registry.names()).containsExactly("local_search");
        assertThat(registry.require("docs__read", "remote-v2")).isSameAs(second);
    }

    @Test
    void rejectsCrossSourceCollisionsWithoutPublishingPartialReplacement() {
        TestTool local = new TestTool("search", "local-v1", AgentTool.LOCAL_SOURCE);
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(local), AgentToolRegistrationPolicy.ALLOW_MUTATING);

        assertThatThrownBy(() -> registry.replaceSource(
                "mcp:docs", List.of(new TestTool("search", "remote-v1", "mcp:docs"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicts across sources");
        assertThat(registry.names()).containsExactly("search");
        assertThat(registry.namesBySource("mcp:docs")).isEmpty();
    }

    private record TestTool(String name, String version, String source) implements AgentTool {
        @Override
        public AgentToolSchema schema() {
            return new AgentToolSchema(version, Map.of());
        }

        @Override
        public Effect effect() {
            return Effect.READ_ONLY;
        }

        @Override
        public AgentToolResult execute(AgentToolContext context) {
            return AgentToolResult.success(Map.of(), null);
        }
    }
}
