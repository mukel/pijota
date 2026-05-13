package ai.pijota.extensions.system;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime;
import ai.pijota.modes.SlashCommand;

import java.util.List;

public final class SystemPromptExtension implements Extension {

    @Override public String name() { return "system-prompt"; }
    @Override public String description() { return "Shows and prints the generated system prompt"; }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.onAgentStart((_ev, _ctx) -> updateStatus(runtime));
        api.onSessionShutdown((_ev, _ctx) -> runtime.clearStatus("system-prompt"));

        api.registerSlashCommand(new SlashCommand("system-prompt",
            "Print the current generated system prompt", List.of("sys"), _args -> {
                String prompt = runtime.systemPrompt();
                if (prompt == null || prompt.isBlank()) {
                    runtime.setStatus("system prompt is empty");
                    return;
                }
                runtime.appendStatus("system prompt (" + prompt.length() + " chars):\n" + prompt);
            }));
    }

    private static void updateStatus(ExtensionRuntime runtime) {
        String prompt = runtime.systemPrompt();
        int chars = prompt != null ? prompt.length() : 0;
        runtime.setStatus("system-prompt", "system prompt: " + chars + " chars");
    }
}
