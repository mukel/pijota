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
                                    "Web browsing via a real browser (--browse=chrome|firefox|safari)"),
                                    new Spec("jobs", true, "Background shell jobs (jobs_run / jobs_output / jobs_list / jobs_kill)"),
                                    new Spec("suggest", false, "Suggest the next message after a turn; Tab accepts (--suggest=<text|file> to customise)"),
                                    new Spec("tmux", false, "Supervise long-lived processes via tmux"),
                                    new Spec("todo", false, "Structured todo list with a live panel"),
                                    new Spec("token-speed", false, "Tokens/sec in the status bar"), new Spec(
                                                                    "transcendence", false,
                                                                    "Self-modification: edit/build/test/respawn pijota (JVM only)"),
                                    new Spec("whoami", false, "Model/provider info tool"),
                                    new Spec("skills", true, "Skill auto-discovery"),
                                    new Spec("prompt-templates", true, "Prompt-template slash commands"),
                                    new Spec("context-files", true, "AGENTS.md / CLAUDE.md context"));

    /**
     * Where an extension's description starts. The same column {@code --help} gives its OPTIONS descriptions: both
     * sections are flag lists in one document, and two flag columns four apart read as two documents.
     */
    private static final int DESCRIPTION_COLUMN = 28;

    /** {@code --help} is read in a terminal, and 80 columns is the one width every terminal has. */
    private static final int HELP_WIDTH = 80;

    /** The {@code --help} block: one line per extension showing the flag that flips it from its default state. */
    public static String helpSection() {
        var sb = new StringBuilder("EXTENSIONS (--<name> enables, --no-<name> disables; persist in settings.json\n"
                                        + "            \"extensions\"):\n");
        for (var s : TOGGLEABLE) {
            String flag = s.enabledByDefault() ? "--no-" + s.name() : "--" + s.name();
            String state = s.enabledByDefault() ? "on" : "off";
            sb.append(wrapped(flag, s.summary() + " [default: " + state + "]"));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * One entry: the flag, then its description wrapped to {@link #HELP_WIDTH} with the continuations hanging under the
     * description rather than under the flag. Unwrapped, the longest of these ran to 127 columns, so on the terminal
     * this text exists for it folded back to column zero and the flag column stopped being a column.
     */
    private static String wrapped(String flag, String description) {
        String indent = " ".repeat(DESCRIPTION_COLUMN);
        var sb = new StringBuilder(String.format("  %-" + (DESCRIPTION_COLUMN - 2) + "s", flag));
        if (flag.length() > DESCRIPTION_COLUMN - 3) // too long to share its row; the description starts on the next
            sb.append('\n').append(indent);
        int room = HELP_WIDTH - DESCRIPTION_COLUMN;
        int used = 0;
        for (String word : description.split(" ")) {
            if (used > 0 && used + 1 + word.length() > room) {
                sb.append('\n').append(indent);
                used = 0;
            } else if (used > 0) {
                sb.append(' ');
                used++;
            }
            sb.append(word);
            used += word.length();
        }
        return sb.append('\n').toString();
    }
}
