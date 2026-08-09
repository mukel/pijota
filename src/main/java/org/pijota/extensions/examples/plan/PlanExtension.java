package org.pijota.extensions.examples.plan;

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
import java.util.Set;

/** Read-only mode: the mutating tools are withheld, so exploring a codebase cannot change it. */
public final class PlanExtension implements Extension {
    /**
     * The builtins plan mode withholds. Everything else the session had stays, because a read-only mode has no reason
     * to take away webfetch, websearch, todo or an extension's tools -- and the allow-list this replaces did exactly
     * that, silently narrowing a twenty-tool session to six on the way in.
     */
    private static final Set<String> WITHHELD = Set.of("edit", "write", "apply_patch");

    private static final String PLAN_REMINDER = """

                                    <system-reminder>
                                    [PLAN MODE ACTIVE] Read-only exploration. The edit, write and apply_patch tools are
                                    withheld and bash is limited to read-only commands; everything else this session had
                                    is still available. Do not attempt to make changes. Answer with a numbered plan
                                    under a "Plan:" header describing what you would do.
                                    </system-reminder>""";

    private volatile boolean planEnabled;
    /** What was active when plan mode started, so leaving it gives back exactly that. Null when it never started. */
    private volatile List<String> toolsBeforePlan;

    @Override
    public String name() {
        return "plan";
    }

    @Override
    public boolean enabledByDefault() {
        return false; // a demonstration of the extension API, not something every session needs; enable with --plan
    }

    @Override
    public String description() {
        return "Read-only plan mode for safe code exploration. Enable with --plan.";
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

        // Attached to the turn, not to the system prompt. The system prompt is the front of every request, so
        // toggling plan mode there changed the cached prefix and threw away the provider-side cache twice per
        // on/off cycle. A reminder rides the message instead: the prefix is byte-identical whether plan mode is on
        // or off, and history strips reminders on the next submit so they do not accumulate.
        api.onInput(text -> planEnabled ? new ExtensionResult.InputResult(text + PLAN_REMINDER, false) : null);

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
        // firing again on /new -- would otherwise record the reduced set as what to go back to, and leaving would
        // give back the tools plan mode had just imposed.
        if (toolsBeforePlan == null)
            toolsBeforePlan = names(api.runtime().activeTools());
        api.runtime().setActiveTools(toolsBeforePlan.stream().filter(name -> !WITHHELD.contains(name)).toList());
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
