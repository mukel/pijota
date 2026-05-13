package ai.pijota.extensions.examples;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class HelloExtension implements Extension {

    @Override public String name() { return "hello"; }
    @Override public String description() { return "Minimal custom tool example"; }

    @Override
    public void init(ExtensionAPI api) {
        api.registerTool(new Tool.ToolDefinition(
            "hello",
            "A simple greeting tool",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string", "description", "Name to greet")
                ),
                "required", List.of("name")
            ),
            (id, args, cwd) -> {
                String name = (String) args.get("name");
                return CompletableFuture.completedFuture(
                    Tool.ToolResult.success("Hello, " + name + "!",
                        Map.of("greeted", name)));
            }
        ));
    }
}
