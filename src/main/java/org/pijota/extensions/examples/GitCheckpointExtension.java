package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionRuntime.ExecResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Creates a git stash checkpoint before each turn so the user can restore code to any previous turn. Tracks the last
 * modified tool to annotate checkpoints with what was changed. Not registered by default — intended as a demo of the
 * event + exec extension pattern.
 */
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

        api.on(ExtensionEvent.ToolResult.class, ev -> {
            lastEntryId.set(ev.toolName() + ":" + ev.toolCallId());
        });

        api.on(ExtensionEvent.TurnStart.class, _ev -> {
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

        api.on(ExtensionEvent.AgentEnd.class, _ev -> {
            synchronized (checkpoints) {
                checkpoints.clear();
            }
        });
    }
}
