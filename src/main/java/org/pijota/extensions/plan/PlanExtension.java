package org.pijota.extensions.plan;

import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionKeybinding;
import org.pijota.extensions.ExtensionResult;
import org.pijota.extensions.KeyChord;
import org.pijota.modes.SlashCommand;
import org.pijota.tools.Tool;
import org.pijota.util.Util;

import java.util.List;

/** Read-only mode: the mutating tools are withheld, so exploring a codebase cannot change it. */
public final class PlanExtension implements Extension {
    private static final List<String> PLAN_TOOLS = List.of("read", "bash", "grep", "glob", "ls", "question");

    private volatile boolean planEnabled;
    /** What was active when plan mode started, so leaving it gives back exactly that. Null when it never started. */
    private volatile List<String> toolsBeforePlan;

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
                enter(api);
            }
        });
    }

    private void toggle(ExtensionAPI api) {
        planEnabled = !planEnabled;
        if (planEnabled)
            enter(api);
        else
            leave(api);
    }

    private void enter(ExtensionAPI api) {
        // Captured once. Entering twice -- a session that starts with the plan flag after a toggle, or SessionStart
        // firing again on /new -- would otherwise record the plan set as what to go back to, and leaving would give
        // back the six tools plan mode had just imposed.
        if (toolsBeforePlan == null)
            toolsBeforePlan = names(api.runtime().activeTools());
        api.runtime().setActiveTools(PLAN_TOOLS);
    }

    /**
     * Gives back the tools plan mode borrowed.
     *
     * <p>
     * It used to restore a hardcoded seven, so leaving plan mode was a one-way door: a session that started with twenty
     * tools came back with read, bash, edit, write, grep, glob and ls, and webfetch, websearch, question, todo, the
     * jobs and task tools, schedule_prompt and skill were gone for the rest of it. Nothing said so. The list was also a
     * guess at what "normal" means, which no list in this position can know: it depends on the flags this run was
     * started with, what the policy allows, and what extensions registered.
     *
     */
    private void leave(ExtensionAPI api) {
        var before = toolsBeforePlan;
        if (before == null)
            return; // never entered, so nothing was taken away and there is nothing to give back
        api.runtime().setActiveTools(before);
        toolsBeforePlan = null;
    }

    private static List<String> names(List<Tool.ToolInfo> tools) {
        return tools.stream().map(Tool.ToolInfo::name).toList();
    }

}
