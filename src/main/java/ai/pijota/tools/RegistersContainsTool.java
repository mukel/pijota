package ai.pijota.tools;

import ai.pijota.registers.RegisterService;
import ai.pijota.util.Util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class RegistersContainsTool implements Tool {
    private final RegisterService registers;

    public RegistersContainsTool(RegisterService registers) {
        this.registers = registers;
    }

    @Override
    public String name() {
        return "registers_contains";
    }

    @Override
    public String description() {
        return "Find register keys matching a glob query.";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")), "required",
                                        List.of("query"), "additionalProperties", false);
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Map<String, Object> args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            Object query = args.get("query");
            if (!(query instanceof String s))
                return ToolResult.error("query must be string");
            try {
                return ToolResult.success(Util.json(Map.of("matches", registers.contains(s))));
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        });
    }
}
