package io.seekflux.platform.agentruntime.domain.service.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Source-aware Tool registry. Source replacement is atomic, while a bounded set of old Schema
 * versions remains addressable for in-flight execution and checkpoint recovery.
 */
public final class AgentToolRegistry {

    private static final int MAX_VERSIONS_PER_TOOL = 16;

    private final AgentToolRegistrationPolicy registrationPolicy;
    private volatile RegistryState state;

    public AgentToolRegistry(Collection<? extends AgentTool> tools) {
        this(tools, AgentToolRegistrationPolicy.SAFE_ONLY);
    }

    public AgentToolRegistry(
            Collection<? extends AgentTool> tools,
            AgentToolRegistrationPolicy registrationPolicy) {
        this.registrationPolicy = java.util.Objects.requireNonNull(
                registrationPolicy, "Tool registration policy must not be null");
        Map<String, AgentTool> indexed = index(tools, null);
        Map<ToolVersion, AgentTool> versioned = new LinkedHashMap<>();
        indexed.values().forEach(tool -> versioned.put(version(tool), tool));
        this.state = new RegistryState(Map.copyOf(indexed), Map.copyOf(versioned));
    }

    public boolean containsMutating() {
        return state.current().values().stream()
                .anyMatch(tool -> tool.effect() == AgentTool.Effect.MUTATING);
    }

    public boolean containsMutating(Collection<String> names) {
        return names.stream().map(this::require)
                .anyMatch(tool -> tool.effect() == AgentTool.Effect.MUTATING);
    }

    public boolean containsMutating(
            Collection<String> names,
            Map<String, String> frozenSchemaVersions) {
        return names.stream()
                .map(name -> require(name, frozenSchemaVersions.get(name)))
                .anyMatch(tool -> tool.effect() == AgentTool.Effect.MUTATING);
    }

    public AgentTool require(String name) {
        AgentTool tool = state.current().get(name);
        if (tool == null) {
            throw new IllegalArgumentException("unknown agent tool: " + name);
        }
        return tool;
    }

    public AgentTool require(String name, String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("Tool schema version is required: " + name);
        }
        AgentTool tool = state.versioned().get(new ToolVersion(name, schemaVersion));
        if (tool == null) {
            throw new IllegalStateException(
                    "frozen Tool schema version is unavailable: " + name + "@" + schemaVersion);
        }
        return tool;
    }

    public Map<String, String> versionsFor(Collection<String> names) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (String name : names) {
            AgentTool tool = require(name);
            versions.put(name, tool.schema().version());
        }
        return Map.copyOf(versions);
    }

    public Set<String> names() {
        return state.current().keySet();
    }

    public Set<String> namesBySource(String source) {
        String normalized = requireSource(source);
        Set<String> names = new LinkedHashSet<>();
        state.current().forEach((name, tool) -> {
            if (normalized.equals(tool.source())) {
                names.add(name);
            }
        });
        return Set.copyOf(names);
    }

    /** Replaces every currently visible Tool from one non-local source in a single publication. */
    public synchronized Set<String> replaceSource(
            String source,
            Collection<? extends AgentTool> replacements) {
        String normalized = requireDynamicSource(source);
        Map<String, AgentTool> nextSource = index(replacements, normalized);
        RegistryState previous = state;
        Map<String, AgentTool> next = new LinkedHashMap<>();
        previous.current().forEach((name, tool) -> {
            if (!normalized.equals(tool.source())) {
                next.put(name, tool);
            }
        });
        for (AgentTool tool : nextSource.values()) {
            AgentTool conflict = next.putIfAbsent(tool.name(), tool);
            if (conflict != null) {
                throw new IllegalArgumentException(
                        "agent Tool name conflicts across sources: " + tool.name());
            }
        }
        Map<ToolVersion, AgentTool> versions = new LinkedHashMap<>(previous.versioned());
        nextSource.values().forEach(tool -> versions.put(version(tool), tool));
        trimHistory(versions, next);
        state = new RegistryState(Map.copyOf(next), Map.copyOf(versions));
        return Set.copyOf(nextSource.keySet());
    }

    /** Removes only current visibility. Archived versions remain recoverable until history eviction. */
    public synchronized Set<String> unregisterSource(String source) {
        String normalized = requireDynamicSource(source);
        RegistryState previous = state;
        Set<String> removed = new LinkedHashSet<>();
        Map<String, AgentTool> next = new LinkedHashMap<>();
        previous.current().forEach((name, tool) -> {
            if (normalized.equals(tool.source())) {
                removed.add(name);
            } else {
                next.put(name, tool);
            }
        });
        state = new RegistryState(Map.copyOf(next), previous.versioned());
        return Set.copyOf(removed);
    }

    private Map<String, AgentTool> index(
            Collection<? extends AgentTool> tools,
            String requiredSource) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        if (tools == null) {
            return indexed;
        }
        for (AgentTool tool : tools) {
            if (tool == null) {
                throw new IllegalArgumentException("agent Tool must not be null");
            }
            requireSource(tool.source());
            if (requiredSource != null && !requiredSource.equals(tool.source())) {
                throw new IllegalArgumentException(
                        "agent Tool source does not match replacement source: " + tool.name());
            }
            if (!registrationPolicy.allow(tool)) {
                throw new IllegalArgumentException(
                        "agent Tool registration denied: " + tool.name() + " [" + tool.effect() + "]");
            }
            AgentTool previous = indexed.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate agent tool: " + tool.name());
            }
        }
        return indexed;
    }

    private static void trimHistory(
            Map<ToolVersion, AgentTool> versions,
            Map<String, AgentTool> current) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolVersion key : versions.keySet()) {
            counts.merge(key.name(), 1, Integer::sum);
        }
        var iterator = versions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ToolVersion, AgentTool> entry = iterator.next();
            ToolVersion key = entry.getKey();
            if (counts.getOrDefault(key.name(), 0) <= MAX_VERSIONS_PER_TOOL) {
                continue;
            }
            AgentTool visible = current.get(key.name());
            boolean currentVersion = visible != null
                    && visible.schema().version().equals(key.schemaVersion());
            if (!currentVersion) {
                iterator.remove();
                counts.computeIfPresent(key.name(), (ignored, count) -> count - 1);
            }
        }
    }

    private static ToolVersion version(AgentTool tool) {
        return new ToolVersion(tool.name(), tool.schema().version());
    }

    private static String requireDynamicSource(String source) {
        String normalized = requireSource(source);
        if (AgentTool.LOCAL_SOURCE.equals(normalized)) {
            throw new IllegalArgumentException("the local Tool source cannot be dynamically replaced");
        }
        return normalized;
    }

    private static String requireSource(String source) {
        if (source == null || source.isBlank() || source.length() > 128) {
            throw new IllegalArgumentException("agent Tool source must contain at most 128 characters");
        }
        return source.trim();
    }

    private record ToolVersion(String name, String schemaVersion) {
    }

    private record RegistryState(
            Map<String, AgentTool> current,
            Map<ToolVersion, AgentTool> versioned) {
    }
}
