package ai.pijota.tools;

import ai.pijota.llm.ModelRegistry;
import ai.pijota.llm.ToolSchema;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class WhoamiTool implements Tool {

    private volatile String currentModel;
    private volatile String currentProvider;

    public void setModelInfo(String provider, String model) {
        this.currentProvider = provider;
        this.currentModel = model;
    }

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public String description() {
        return "Get information about the current model being used: its id, provider, display name, and context window size. "
                                        + "Use this tool when you need to know which model is currently active, e.g. to verify capabilities or context limits.";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchema.object().build();
    }

    @Override
    public CompletableFuture<ToolResult> execute(String toolCallId, Args args, String sessionCwd) {
        return CompletableFuture.supplyAsync(() -> {
            String model = currentModel;
            String provider = currentProvider;

            if (model == null || provider == null) {
                return ToolResult.error("Model information not available");
            }

            var info = ModelRegistry.byId(model).orElse(null);

            var sb = new StringBuilder();
            sb.append("id: ").append(model).append('\n');
            sb.append("provider: ").append(provider).append('\n');
            if (info != null) {
                sb.append("name: ").append(info.displayName()).append('\n');
                sb.append("context: ").append(info.contextWindow()).append(" tokens").append('\n');
            }

            return ToolResult.success(sb.toString().trim());
        });
    }
}
