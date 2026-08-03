package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A remappable action is only remappable if you can learn its name. The names are string literals passed to
 * {@code UserKeybindings.keysFor}/{@code remaps}, so nothing connects them to the page that lists them, and the first
 * draft of that page invented two ({@code "palette"}, {@code "model"}) that would have silently done nothing in a
 * user's keybindings.json.
 */
class KeybindingActionsAreDocumentedTest {

    @Test
    void everyRemappableActionIsNamedInTheDocs() throws IOException {
        String source = Files.readString(Path.of("src/main/java/org/pijota/tui/AppendOnlyTui.java"),
                                        StandardCharsets.UTF_8);
        String docs = Files.readString(Path.of("docs/keybindings.md"), StandardCharsets.UTF_8);

        var undocumented = new TreeSet<String>();
        var actions = Pattern.compile("\"((?:app|tui)\\.[a-zA-Z.]+)\"").matcher(source);
        while (actions.find())
            if (!docs.contains("`" + actions.group(1) + "`") && !docs.contains(shortForm(actions.group(1))))
                undocumented.add(actions.group(1));

        assertFalse(docs.contains("\"palette\""), "the invented action name is back in the docs");
        assertTrue(undocumented.isEmpty(),
                                        "keybinding actions with no mention in docs/keybindings.md: " + undocumented);
    }

    /** The page lists families compactly ("cursorUp / cursorDown"), so the trailing segment counts as a mention. */
    private static String shortForm(String action) {
        return "`" + action.substring(action.lastIndexOf('.') + 1);
    }
}
