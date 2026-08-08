package io.seekflux.platform.agentruntime;

public record AgentToolParameter(
        Type type,
        boolean required,
        Integer maxLength,
        Integer maxItems,
        Long minimum,
        Long maximum) {

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
