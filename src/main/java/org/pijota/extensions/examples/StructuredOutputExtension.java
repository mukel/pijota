package org.pijota.extensions.examples;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.tools.Tool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Registers a {@code structured_output} tool that lets the model return machine-readable final answers with a headline,
 * summary, and action-items array. Demonstrates a typed multi-field tool definition. Not registered by default.
 */
public final class StructuredOutputExtension implements Extension {

    @Override
    public String name() {
        return "structured-output";
    }

    @Override
    public String description() {
        return "Structured final answer tool with typed parameters";
    }

    @Override
    public void init(ExtensionAPI api) {
        api.registerTool(new Tool.ToolDefinition("structured_output",
                                        "Return a final structured answer. Use as your last action when asked for machine-readable summaries.",
                                        Map.of("type", "object", "properties", Map.of("headline",
                                                                        Map.of("type", "string", "description",
                                                                                                        "Short title"),
                                                                        "summary",
                                                                        Map.of("type", "string", "description",
                                                                                                        "One-paragraph summary"),
                                                                        "actionItems",
                                                                        Map.of("type", "array", "items", Map.of("type",
                                                                                                        "string"),
                                                                                                        "description",
                                                                                                        "Concrete next steps or key bullets")),
                                                                        "required",
                                                                        List.of("headline", "summary", "actionItems")),
                                        (id, args, cwd) -> {
                                            String headline = (String) args.get("headline");
                                            String summary = (String) args.get("summary");
                                            @SuppressWarnings("unchecked")
                                            List<String> actionItems = (List<String>) args.get("actionItems");
                                            if (actionItems == null)
                                                actionItems = List.of();

                                            return CompletableFuture.completedFuture(Tool.ToolResult.success(
                                                                            "Saved structured output: " + headline,
                                                                            Map.of("headline", headline, "summary",
                                                                                                            summary,
                                                                                                            "actionItems",
                                                                                                            actionItems)));
                                        }));
    }
}
