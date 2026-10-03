package io.seekflux.platform.agentruntime.infrastructure.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaces the active ToolGroup set for the next model turn. */
public final class SwitchToolGroupsTool implements AgentTool {

    public static final String NAME = "switch_tool_groups";
    private static final String ACTIVE_GROUPS = "active_groups";
    private final CapabilityCatalog catalog;

    public SwitchToolGroupsTool(CapabilityCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "替换当前激活的工具组集合，从下一模型轮生效。只能使用上下文中允许的组 ID，不授予额外权限；alwaysActive 组保持有效。";
    }

    @Override
    public AgentToolSchema schema() {
        return new AgentToolSchema(
                "switch-tool-groups-v2",
                Map.of(ACTIVE_GROUPS,
                        new AgentToolParameter(
                                AgentToolParameter.Type.STRING_LIST,
                                true,
                                128,
                                64,
                                null,
                                null).withDescription("希望激活的完整工具组 ID 列表，而不是增量；必须至少提供一个允许的组。")));
    }

    @Override
    public Effect effect() {
        return Effect.READ_ONLY;
    }

    @Override
    public AgentToolResult execute(AgentToolContext context) {
        Object raw = context.arguments().get(ACTIVE_GROUPS);
        if (!(raw instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            return AgentToolResult.failure("TOOL_GROUP_SWITCH_INVALID");
        }
        Set<String> groups = values.stream()
                .map(String.class::cast)
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (groups.isEmpty() || !catalog.toolGroups().keySet().containsAll(groups)) {
            return AgentToolResult.failure("TOOL_GROUP_SWITCH_INVALID");
        }
        List<String> ordered = groups.stream().sorted().toList();
        return AgentToolResult.switchToolGroups(
                Map.of("activeGroups", ordered), null, groups);
    }
}
