package ai.pijota.modes;

import java.util.List;
import java.util.function.Consumer;

public record SlashCommand(String name, String description, List<String> aliases, Consumer<String> handler) {

    /** True if {@code input} names this command or one of its aliases (case-insensitive, leading "/" ignored). */
    public boolean matches(String input) {
        if (matchesToken(name, input))
            return true;
        for (String alias : aliases)
            if (matchesToken(alias, input))
                return true;
        return false;
    }

    private static boolean matchesToken(String token, String input) {
        return strip(token).equalsIgnoreCase(strip(input));
    }

    private static String strip(String s) {
        return s.startsWith("/") ? s.substring(1) : s;
    }
}
