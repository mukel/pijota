package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.tools.Tool;

import java.util.Map;

/**
 * Minimal custom-tool example — registers a {@code hello} tool with the fluent {@link Tool#named} builder (the schema
 * is generated from the declared params). The simplest possible extension; not registered by default.
 */
public final class HelloExtension implements Extension {

    @Override
    public String name() {
        return "hello";
    }

    @Override
    public String description() {
        return "Minimal custom tool example";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerTool(Tool.named("hello").description("A simple greeting tool")
                                        .stringParam("name", "Name to greet", true).run((args, cwd) -> {
                                            String name = args.getString("name");
                                            return Tool.ToolResult.success("Hello, " + name + "!",
                                                                            Map.of("greeted", name));
                                        }));
    }
}
