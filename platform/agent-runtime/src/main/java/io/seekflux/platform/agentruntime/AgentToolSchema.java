package io.seekflux.platform.agentruntime;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AgentToolSchema(String version, Map<String, AgentToolParameter> parameters) {

    public AgentToolSchema {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("tool schema version must not be blank");
        }
        version = version.trim();
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "tool parameters must not be null"));
    }

    public void validate(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "tool arguments must not be null");
        for (String key : arguments.keySet()) {
            if (!parameters.containsKey(key)) {
                throw new IllegalArgumentException("unsupported tool argument: " + key);
            }
        }
        parameters.forEach((name, parameter) -> validateValue(name, parameter, arguments.get(name)));
    }

    public Map<String, Object> repair(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "tool arguments must not be null");
        Map<String, Object> repaired = new LinkedHashMap<>();
        parameters.forEach((name, parameter) -> {
            Object value = arguments.get(name);
            if (value == null) {
                return;
            }
            Object normalized = switch (parameter.type()) {
                case INTEGER -> repairInteger(value);
                case BOOLEAN -> repairBoolean(value);
                case STRING_LIST -> repairStringList(value);
                case STRING -> value instanceof String text ? text.trim() : value;
            };
            repaired.put(name, normalized);
        });
        return Map.copyOf(repaired);
    }

    private static Object repairInteger(Object value) {
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static Object repairBoolean(Object value) {
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(text.trim())) {
                return false;
            }
        }
        return value;
    }

    private static Object repairStringList(Object value) {
        if (value instanceof String text) {
            return java.util.Arrays.stream(text.split("[,，]"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return value;
    }

    private static void validateValue(String name, AgentToolParameter parameter, Object value) {
        if (value == null) {
            if (parameter.required()) {
                throw new IllegalArgumentException("missing required tool argument: " + name);
            }
            return;
        }
        switch (parameter.type()) {
            case STRING -> {
                if (!(value instanceof String text)) {
                    throw wrongType(name, "string");
                }
                if (parameter.maxLength() != null && text.length() > parameter.maxLength()) {
                    throw new IllegalArgumentException("tool argument " + name + " exceeds max length");
                }
            }
            case INTEGER -> {
                if (!(value instanceof Number number)) {
                    throw wrongType(name, "integer");
                }
                long integer = number.longValue();
                if (number.doubleValue() != integer) {
                    throw wrongType(name, "integer");
                }
                if (parameter.minimum() != null && integer < parameter.minimum()
                        || parameter.maximum() != null && integer > parameter.maximum()) {
                    throw new IllegalArgumentException("tool argument " + name + " is outside its allowed range");
                }
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) {
                    throw wrongType(name, "boolean");
                }
            }
            case STRING_LIST -> {
                if (!(value instanceof List<?> values) || values.stream().anyMatch(item -> !(item instanceof String))) {
                    throw wrongType(name, "string list");
                }
                if (parameter.maxItems() != null && values.size() > parameter.maxItems()) {
                    throw new IllegalArgumentException("tool argument " + name + " has too many items");
                }
                if (parameter.maxLength() != null && values.stream()
                        .map(String.class::cast)
                        .anyMatch(item -> item.length() > parameter.maxLength())) {
                    throw new IllegalArgumentException("tool argument " + name + " contains an oversized item");
                }
            }
        }
    }

    private static IllegalArgumentException wrongType(String name, String expected) {
        return new IllegalArgumentException("tool argument " + name + " must be a " + expected);
    }
}
