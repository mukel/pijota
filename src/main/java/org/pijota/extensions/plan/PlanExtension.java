package org.pijota.extensions.plan;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionKeybinding;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.KeyChord;
import org.pijota.modes.SlashCommand;
import org.pijota.util.Util;

import java.util.List;

/** Read-only mode: the mutating tools are withheld, so exploring a codebase cannot change it. */
public final class PlanExtension implements Extension {
    private static final List<String> PLAN_TOOLS = List.of("read", "bash", "grep", "glob", "ls", "question");
    private static final List<String> NORMAL_TOOLS = List.of("read", "bash", "edit", "write", "grep", "glob", "ls");

    private volatile boolean planEnabled;

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public String description() {
        return "Read-only plan mode for safe code exploration";
    }

    @Override
    public void init(ExtensionAPI api) throws Exception {
        var runtime = api.runtime();

        api.registerSlashCommand(new SlashCommand("plan", "Toggle plan mode (read-only exploration)", List.of(),
                                        _args -> toggle(api)));

        api.registerKeybinding(new ExtensionKeybinding(
                                        new KeyChord(KeyChord.Key.P, KeyChord.Modifier.CTRL, KeyChord.Modifier.ALT),
                                        "Toggle plan mode", () -> toggle(api)));

        api.registerStatusLines(() -> planEnabled ? List.of("\u23f8 plan") : List.of());

        api.onToolCall(tc -> {
            if (!planEnabled)
                return null;
            if (!"bash".equals(tc.toolName()))
                return null;
            String command = Util.parseBashArgs(tc.arguments());
            if (command != null && !PlanUtils.isSafeCommand(command)) {
                return new ExtensionResult.Block("Plan mode: destructive command blocked. /plan to exit.");
            }
            return null;
        });

        api.on(ExtensionEvent.BeforeAgentStart.class, bas -> {
            if (!planEnabled)
                return;
            bas.systemPrompt().append("""

                                            [PLAN MODE ACTIVE]
                                            You are in plan mode, read-only exploration. Available tools: read, bash (safe commands only), grep, glob, ls, question. Edit/write are disabled.
                                            Create a numbered plan under a "Plan:" header:
                                            Plan:
                                            1. First step description
                                            2. Second step description
                                            ...
                                            Do NOT attempt to make changes, just describe what you would do.""");
        });

        api.on(ExtensionEvent.SessionStart.class, _event -> {
            if (runtime.getFlag("plan", false)) {
                planEnabled = true;
                runtime.setActiveTools(PLAN_TOOLS);
            }
        });
    }

    private void toggle(ExtensionAPI api) {
        planEnabled = !planEnabled;
        if (planEnabled) {
            api.runtime().setActiveTools(PLAN_TOOLS);
        } else {
            api.runtime().setActiveTools(NORMAL_TOOLS);
        }
    }

}
