package io.seekflux.agent.infrastructure.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts untrusted MCP JSON Schema into the deliberately smaller local Tool Schema. */
public final class McpSchemaTranslator {

    private static final int MAX_SCHEMA_BYTES = 64 * 1024;
    private static final int MAX_PROPERTIES = 32;
    private static final int MAX_SCHEMA_DEPTH = 8;
    private static final int MAX_SCHEMA_NODES = 512;
    private static final int MAX_STRING_LENGTH = 4_096;
    private static final int MAX_ARRAY_ITEMS = 64;

    private final ObjectMapper objectMapper;

    public McpSchemaTranslator(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(
                objectMapper, "ObjectMapper must not be null");
    }

    public TranslatedTool translate(
            McpServerConfig server,
            McpRemoteTool remote,
            McpToolPolicy policy,
            String reconciliationSchemaHash) {
        String schemaHash = schemaHash(remote);
        Map<String, Object> input = remote.inputSchema();
        if (!"object".equals(input.get("type"))) {
            throw new IllegalArgumentException("MCP Tool input Schema root must be an object");
        }
        Map<String, Object> properties = objectMap(input.get("properties"));
        if (properties.size() > MAX_PROPERTIES) {
            throw new IllegalArgumentException("MCP Tool input Schema has too many properties");
        }
        Set<String> required = stringSet(input.get("required"));
        if (!properties.keySet().containsAll(required)) {
            throw new IllegalArgumentException("MCP Tool required field is missing from properties");
        }
        Map<String, AgentToolParameter> parameters = new LinkedHashMap<>();
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String name = McpServerConfig.text(entry.getKey(), "MCP Tool property", 128);
            Map<String, Object> property = objectMap(entry.getValue());
            String type = String.valueOf(property.get("type"));
            boolean isRequired = required.contains(name);
            AgentToolParameter translated = switch (type) {
                case "string" -> new AgentToolParameter(
                        AgentToolParameter.Type.STRING,
                        isRequired,
                        boundedInt(property.get("maxLength"), MAX_STRING_LENGTH),
                        null,
                        null,
                        null);
                case "integer" -> new AgentToolParameter(
                        AgentToolParameter.Type.INTEGER,
                        isRequired,
                        null,
                        null,
                        longValue(property.get("minimum")),
                        longValue(property.get("maximum")));
                case "boolean" -> new AgentToolParameter(
                        AgentToolParameter.Type.BOOLEAN,
                        isRequired,
                        null,
                        null,
                        null,
                        null);
                case "array" -> stringArray(property, isRequired);
                default -> throw new IllegalArgumentException(
                        "unsupported MCP Tool property type: " + type);
            };
            parameters.put(name, translated);
        });
        StringBuilder version = new StringBuilder("mcp-")
                .append(server.configVersion()).append('-')
                .append(policy.policyVersion()).append('-')
                .append(schemaHash);
        if (reconciliationSchemaHash != null && !reconciliationSchemaHash.isBlank()) {
            version.append("-reconcile-").append(reconciliationSchemaHash);
        }
        return new TranslatedTool(
                new AgentToolSchema(version.toString(), parameters),
                schemaHash,
                remote.description());
    }

    public String schemaHash(McpRemoteTool remote) {
        validateShape(remote.inputSchema(), 0, new int[] {0});
        byte[] serialized;
        try {
            serialized = objectMapper.writeValueAsBytes(remote.inputSchema());
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("MCP Tool Schema is not serializable", invalid);
        }
        if (serialized.length > MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("MCP Tool Schema exceeds 65536 bytes");
        }
        return sha256(canonicalValue(remote.inputSchema()));
    }

    private static void validateShape(Object value, int depth, int[] nodes) {
        if (depth > MAX_SCHEMA_DEPTH || ++nodes[0] > MAX_SCHEMA_NODES) {
            throw new IllegalArgumentException("MCP Tool Schema depth or node limit exceeded");
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((ignored, child) -> validateShape(child, depth + 1, nodes));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> validateShape(child, depth + 1, nodes));
        }
    }

    private static AgentToolParameter stringArray(
            Map<String, Object> property,
            boolean required) {
        Map<String, Object> items = objectMap(property.get("items"));
        if (!"string".equals(items.get("type"))) {
            throw new IllegalArgumentException("only arrays of strings are supported for MCP Tools");
        }
        return new AgentToolParameter(
                AgentToolParameter.Type.STRING_LIST,
                required,
                boundedInt(items.get("maxLength"), MAX_STRING_LENGTH),
                boundedInt(property.get("maxItems"), MAX_ARRAY_ITEMS),
                null,
                null);
    }

    private static Integer boundedInt(Object raw, int defaultMaximum) {
        if (raw == null) {
            return defaultMaximum;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("MCP Schema size bound must be numeric");
        }
        long value = number.longValue();
        if (value < 0) {
            throw new IllegalArgumentException("MCP Schema size bound must not be negative");
        }
        return Math.toIntExact(Math.min(value, defaultMaximum));
    }

    private static Long longValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("MCP integer bound must be numeric");
        }
        double value = number.doubleValue();
        if (value != number.longValue()) {
            throw new IllegalArgumentException("MCP integer bound must be an integer");
        }
        return number.longValue();
    }

    private static Map<String, Object> objectMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("MCP Schema object was expected");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return Map.copyOf(result);
    }

    private static Set<String> stringSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (!(raw instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("MCP Schema required must be a string array");
        }
        return values.stream().map(String.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            return sorted.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + canonicalValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            list.forEach(item -> values.add(canonicalValue(item)));
            return String.join(",", values);
        }
        return String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record TranslatedTool(
            AgentToolSchema schema,
            String remoteSchemaHash,
            String description) {
    }
}
