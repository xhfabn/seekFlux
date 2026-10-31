package io.seekflux.platform.agentruntime.domain.service.observation;

import java.lang.reflect.RecordComponent;
import java.util.*;

/** Converts live payloads to bounded immutable values before they enter an asynchronous queue. */
public record ObservationCapturePolicy(boolean captureContent, int maxCharacters,
        int maxNodes, int maxDepth, Set<String> redactedFields) {
    public ObservationCapturePolicy {
        if (maxCharacters < 1 || maxNodes < 1 || maxDepth < 1) {
            throw new IllegalArgumentException("observation capture limits must be positive");
        }
        redactedFields = redactedFields.stream().map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static ObservationCapturePolicy metadataOnly() {
        return new ObservationCapturePolicy(false, 16384, 512, 12,
                Set.of("authorization", "password", "secret", "apikey", "api_key", "access_token"));
    }

    public Map<String, Object> snapshot(Map<String, Object> data) {
        Budget budget = new Budget(maxCharacters, maxNodes);
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            if (budget.nodes <= 0 || budget.characters <= 0) break;
            String key = entry.getKey();
            if (!captureContent && (key.equals("input") || key.equals("output") || key.equals("errorMessage"))) {
                result.put(key, "[not captured]");
            } else {
                result.put(key, value(entry.getValue(), key, 0, budget));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private Object value(Object value, String key, int depth, Budget budget) {
        if (redactedFields.contains(key.toLowerCase(Locale.ROOT))) return "[redacted]";
        if (--budget.nodes < 0 || depth >= maxDepth || budget.characters <= 0) return "[truncated]";
        if (value == null) return "[null]";
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) return value;
        if (value instanceof CharSequence || value instanceof Enum<?> || value instanceof java.time.temporal.TemporalAccessor) {
            String text = value.toString();
            int length = Math.min(text.length(), budget.characters);
            budget.characters -= length;
            return length < text.length() ? text.substring(0, length) + "[truncated]" : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (budget.nodes <= 0 || budget.characters <= 0) { result.put("_truncated", true); break; }
                String field = entry.getKey() instanceof String s ? s : "[non-string-key]";
                String boundedKey = field.substring(0, Math.min(field.length(), Math.min(256, budget.characters)));
                budget.characters -= boundedKey.length();
                result.put(boundedKey, value(entry.getValue(), field, depth + 1, budget));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                if (budget.nodes <= 0 || budget.characters <= 0) { result.add("[truncated]"); break; }
                result.add(value(item, "", depth + 1, budget));
            }
            return List.copyOf(result);
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                if (budget.nodes <= 0 || budget.characters <= 0) { result.put("_truncated", true); break; }
                try {
                    result.put(component.getName(), value(component.getAccessor().invoke(value),
                            component.getName(), depth + 1, budget));
                } catch (ReflectiveOperationException ignored) {
                    result.put(component.getName(), "[unavailable]");
                }
            }
            return Collections.unmodifiableMap(result);
        }
        // Never call arbitrary application toString(), or retain a live mutable object.
        return "[unsupported:" + value.getClass().getName() + "]";
    }

    private static final class Budget {
        int characters;
        int nodes;
        Budget(int characters, int nodes) { this.characters = characters; this.nodes = nodes; }
    }
}
