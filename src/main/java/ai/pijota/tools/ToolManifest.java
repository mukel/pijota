package ai.pijota.tools;

import ai.pijota.util.Util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON snapshot + SHA-256 hash of the enabled tool set, used to reuse the cached system prompt tool list
 * while the manifest is unchanged.
 */
public final class ToolManifest {
    private ToolManifest() {}

    public record Snapshot(String json, String hash) {
    }

    public static Snapshot snapshot(ToolRegistry registry) {
        var tools = registry.enabledTools().stream().sorted(Comparator.comparing(Tool::name)).map(tool -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("schema", canonical(tool.schema()));
            return item;
        }).toList();
        try {
            String json = Util.MAPPER.writeValueAsString(tools);
            return new Snapshot(json, sha256(json));
        } catch (Exception e) {
            throw new IllegalStateException("failed to build tool manifest", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> canonicalSchema(Map<String, Object> schema) {
        Object value = canonical(schema);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.entrySet().stream().filter(entry -> entry.getKey() instanceof String)
                                            .sorted(Comparator.comparing(entry -> (String) entry.getKey()))
                                            .forEach(entry -> out.put((String) entry.getKey(),
                                                                            canonical(entry.getValue())));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (var item : list)
                out.add(canonical(item));
            return out;
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : digest)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
