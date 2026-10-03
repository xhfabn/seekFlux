package io.seekflux.platform.agentruntime.domain.model.tool;

public record AgentToolParameter(
        Type type,
        boolean required,
        Integer maxLength,
        Integer maxItems,
        Long minimum,
        Long maximum,
        String description) {

    public AgentToolParameter(Type type, boolean required, Integer maxLength, Integer maxItems,
            Long minimum, Long maximum) {
        this(type, required, maxLength, maxItems, minimum, maximum, "");
    }

    public enum Type {
        STRING,
        INTEGER,
        BOOLEAN,
        STRING_LIST
    }

    public AgentToolParameter {
        if (type == null) {
            throw new IllegalArgumentException("tool parameter type must not be null");
        }
        description = description == null ? "" : description.trim();
        if (description.length() > 2_048) {
            throw new IllegalArgumentException("tool parameter description exceeds 2048 characters");
        }
    }

    public AgentToolParameter withDescription(String description) {
        return new AgentToolParameter(type, required, maxLength, maxItems, minimum, maximum, description);
    }

    public static AgentToolParameter requiredString(int maxLength) {
        return new AgentToolParameter(Type.STRING, true, maxLength, null, null, null);
    }

    public static AgentToolParameter optionalInteger(long minimum, long maximum) {
        return new AgentToolParameter(Type.INTEGER, false, null, null, minimum, maximum);
    }

    public static AgentToolParameter optionalStringList(int maxItems, int maxLength) {
        return new AgentToolParameter(Type.STRING_LIST, false, maxLength, maxItems, null, null);
    }
}
