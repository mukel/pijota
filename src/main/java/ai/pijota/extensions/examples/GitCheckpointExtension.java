package ai.pijota.extensions.examples;

import ai.pijota.extensions.ExtensionEvent;

import ai.pijota.extensions.Extension;
import ai.pijota.extensions.ExtensionAPI;
import ai.pijota.extensions.ExtensionRuntime.ExecResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class GitCheckpointExtension implements Extension {

    private final Map<String, String> checkpoints = new LinkedHashMap<>();
    private final AtomicReference<String> lastEntryId = new AtomicReference<>();

    @Override
    public String name() {
        return "git-checkpoint";
    }

    @Override
    public String description() {
        return "Creates git stash checkpoints at each turn for code restoration";
    }

    @Override
    public void init(ExtensionAPI api) {
        var runtime = api.runtime();

        api.on(ExtensionEvent.ToolResult.class, (_ev, _ctx) -> {
            lastEntryId.set(_ev.toolName() + ":" + _ev.toolCallId());
        });

        api.on(ExtensionEvent.TurnStart.class, (_ev, _ctx) -> {
            try {
                ExecResult result = runtime.exec(List.of("git", "stash", "create"));
                String ref = result.stdout().strip();
                String entryId = lastEntryId.get();
                if (!ref.isBlank() && entryId != null) {
                    synchronized (checkpoints) {
                        checkpoints.put(entryId, ref);
                    }
                }
            } catch (Exception ignored) {}
        });

        api.on(ExtensionEvent.AgentEnd.class, (_ev, _ctx) -> {
            synchronized (checkpoints) {
                checkpoints.clear();
            }
        });
    }
}
