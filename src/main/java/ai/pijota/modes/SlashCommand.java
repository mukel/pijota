package ai.pijota.modes;

import java.util.List;
import java.util.function.Consumer;

/**
 * A user-invokable slash command registered by extensions or built into the core. Commands have a primary name,
 * optional aliases, and a handler that receives the trailing argument text (everything after the command name).
 */
public record SlashCommand(String name, String description, String argumentHint, List<String> aliases,
                                Consumer<String> handler) {

    public SlashCommand(String name, String description, List<String> aliases, Consumer<String> handler) {
        this(name, description, null, aliases, handler);
    }

    /**
     * The command in {@code commands} whose name/alias matches the first whitespace-delimited token of {@code input}
     * (leading "/" optional), or {@code null} if none — the shared command-line lookup every frontend uses.
     */
    public static SlashCommand find(List<SlashCommand> commands, String input) {
        if (input == null)
            return null;
        String token = input.strip().split("\\s+", 2)[0];
        for (SlashCommand c : commands)
            if (c.matches(token))
                return c;
        return null;
    }

    /** True if {@code input} names this command or one of its aliases (case-insensitive, leading "/" ignored). */
    public boolean matches(String input) {
        if (matchesToken(name, input))
            return true;
        for (String alias : aliases)
            if (matchesToken(alias, input))
                return true;
        return false;
    }

    /**
     * The argument text after the command word — everything past the first whitespace-delimited token, stripped. e.g.
     * {@code "/btw what is X"} → {@code "what is X"}, {@code "/plan"} → {@code ""}. Tab- and multi-space-tolerant, so
     * every frontend parses command arguments the same way.
     */
    public static String argsOf(String input) {
        if (input == null)
            return "";
        String s = input.strip();
        for (int i = 0; i < s.length(); i++)
            if (Character.isWhitespace(s.charAt(i)))
                return s.substring(i + 1).strip();
        return "";
    }

    private static boolean matchesToken(String token, String input) {
        return strip(token).equalsIgnoreCase(strip(input));
    }

    private static String strip(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
