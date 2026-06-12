package ai.pijota.tools;

import ai.pijota.registers.RegisterService;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RegistersSetTool implements Tool {
    private final RegisterService registers;

    public RegistersSetTool(RegisterService registers) {
        this.registers = registers;
    }

    @Override
    public String name() {
        return "registers_set";
    }

    @Override
    public String description() {
        return "Write registers.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("values",
                                        Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                                        "delete", Map.of("type", "array", "items", Map.of("type", "string"))),
                                        "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> values = parseValues(args.get("values"));
            if (values == null)
                return ToolResult.error("values must be string map");
            List<String> delete = parseDelete(args.get("delete"));
            if (delete == null)
                return ToolResult.error("delete must be strings");
            var updated = new ArrayList<String>();
            var deleted = new ArrayList<String>();
            var rejected = new LinkedHashMap<String, String>();
            registers.set(values, delete, updated, deleted, rejected);
            var output = new LinkedHashMap<String, Object>();
            output.put("updated", updated);
            output.put("deleted", deleted);
            output.put("rejected", rejected);
            return ToolResult.success(Util.json(output));
        });
    }

    private static Map<String, String> parseValues(Object raw) {
        var values = new LinkedHashMap<String, String>();
        if (raw == null)
            return values;
        if (!(raw instanceof Map<?, ?> map))
            return null;
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value))
                return null;
            values.put(key, value);
        }
        return values;
    }

    private static List<String> parseDelete(Object raw) {
        var delete = new ArrayList<String>();
        if (raw == null)
            return delete;
        if (!(raw instanceof List<?> list))
            return null;
        for (Object value : list) {
            if (!(value instanceof String s))
                return null;
            delete.add(s);
        }
        return delete;
    }
}
