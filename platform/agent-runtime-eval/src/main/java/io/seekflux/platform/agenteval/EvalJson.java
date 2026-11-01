package io.seekflux.platform.agenteval;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.HexFormat;

public final class EvalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .registerModule(new SimpleModule()
                    .addSerializer(Instant.class, ToStringSerializer.instance)
                    .addSerializer(Duration.class, ToStringSerializer.instance));
    private EvalJson() {}
    public static JsonNode tree(Object value) { return MAPPER.valueToTree(value); }
    public static String compact(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("not JSON data", e); }
    }
    public static String pretty(Object value) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("not JSON data", e); }
    }
    public static <T> T read(Path path, Class<T> type) throws IOException {
        return MAPPER.readValue(Files.readString(path), type);
    }
    public static <T> T decode(String json, Class<T> type) throws IOException {
        return MAPPER.readValue(json, type);
    }
    public static void write(Path path, Object value) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, pretty(value) + "\n");
    }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(compact(canonical(tree(value))).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = MAPPER.createObjectNode();
            var names = new java.util.TreeSet<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.forEach(n -> result.set(n, canonical(node.get(n))));
            return result;
        }
        if (node.isArray()) {
            var result = MAPPER.createArrayNode(); node.forEach(n -> result.add(canonical(n))); return result;
        }
        return node;
    }
}
