package ai.pijota.tools;

import ai.pijota.registers.RegisterService;
import ai.pijota.util.Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RegistersGetTool implements Tool {
    private final RegisterService registers;

    public RegistersGetTool(RegisterService registers) {
        this.registers = registers;
    }

    @Override
    public String name() {
        return "registers_get";
    }

    @Override
    public String description() {
        return "Read registers.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties",
                                        Map.of("keys", Map.of("type", "array", "items", Map.of("type", "string"))),
                                        "required", List.of("keys"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            Object raw = args.get("keys");
            if (!(raw instanceof List<?> list))
                return ToolResult.error("keys must be strings");
            var keys = new ArrayList<String>();
            for (Object value : list) {
                if (!(value instanceof String s))
                    return ToolResult.error("keys must be strings");
                keys.add(s);
            }
            var missing = new ArrayList<String>();
            var output = new LinkedHashMap<String, Object>();
            output.put("values", registers.get(keys, missing));
            output.put("missing", missing);
            return ToolResult.success(Util.json(output));
        });
    }
}
