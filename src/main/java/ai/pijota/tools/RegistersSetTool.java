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
        return "Write string register values. Delete scratch keys.";
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
            var values = new LinkedHashMap<String, String>();
            Object raw = args.get("values");
            if (raw != null) {
                if (!(raw instanceof Map<?, ?> map))
                    return ToolResult.error("values must be {\"key\": \"string\", ...}");
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String s))
                        return ToolResult.error("values keys must be strings");
                    if (entry.getValue() == null)
                        return ToolResult.error("values must be {\"key\": \"string\", ...}, null not allowed");
                    if (!(entry.getValue() instanceof String str))
                        return ToolResult.error("register values are always strings, got: " + entry.getValue().getClass().getSimpleName());
                    values.put(s, str);
                }
            }
            List<String> delete = null;
            raw = args.get("delete");
            if (raw != null) {
                if (!(raw instanceof List<?> list))
                    return ToolResult.error("delete must be [string, ...]");
                delete = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof String s))
                        return ToolResult.error("delete must be string array");
                    delete.add(s);
                }
            }
            try {
                var updated = new ArrayList<String>();
                var deleted = new ArrayList<String>();
                var rejected = new LinkedHashMap<String, String>();
                registers.set(values, delete, updated, deleted, rejected);
                var output = new LinkedHashMap<String, Object>();
                output.put("updated", updated);
                output.put("deleted", deleted);
                output.put("rejected", rejected);
                return ToolResult.success(Util.json(output));
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        });
    }
}
