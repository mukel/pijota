package org.pijota.extensions;

import java.util.List;

/**
 * Catalog of the user-toggleable built-in extensions; the single source for rendering the extension section of
 * {@code --help}, so it can't drift from reality. Each is flipped uniformly with {@code --<name>} / {@code --no-<name>}
 * (or persisted in {@code settings.json} {@code "extensions"}), resolved by {@link ExtensionRegistry#isEnabled}. The
 * always-on core extensions (task, goal, schedule, mcp, inline-bash, btw, whimsical) aren't listed; they're not meant
 * to be toggled.
 */
public final class BuiltinExtensions {
    private BuiltinExtensions() {}

    public record Spec(String name, boolean enabledByDefault, String summary) {
    }

    public static final List<Spec> TOGGLEABLE = List.of(new Spec("browse", false,
                                    "web browsing via a real browser (--browse=chrome|firefox|safari)"),
                                    new Spec("jobs", false, "background shell jobs (jobs_run / jobs_output / jobs_list / jobs_kill)"),
                                    new Spec("tmux", false, "supervise long-lived processes via tmux"),
                                    new Spec("todo", false, "structured todo list with a live panel"),
                                    new Spec("token-speed", false, "tokens/sec in the status bar"), new Spec(
                                                                    "transcendence", false,
                                                                    "self-modification: edit/build/test/respawn pijota (JVM only)"),
                                    new Spec("whoami", false, "model/provider info tool"),
                                    new Spec("skills", true, "skill auto-discovery"),
                                    new Spec("prompt-templates", true, "prompt-template slash commands"),
                                    new Spec("context-files", true, "AGENTS.md / CLAUDE.md context"));

    /** The {@code --help} block: one line per extension showing the flag that flips it from its default state. */
    public static String helpSection() {
        var sb = new StringBuilder("EXTENSIONS (--<name> enables, --no-<name> disables; persist in settings.json \"extensions\"):\n");
        for (var s : TOGGLEABLE) {
            String flag = s.enabledByDefault() ? "--no-" + s.name() : "--" + s.name();
            String state = s.enabledByDefault() ? "on" : "off";
            sb.append(String.format("  %-22s %s [default: %s]%n", flag, s.summary(), state));
        }
        return sb.toString().stripTrailing();
    }
}
