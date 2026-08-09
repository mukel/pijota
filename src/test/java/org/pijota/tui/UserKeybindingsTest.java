package org.pijota.tui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UserKeybindingsTest {
    @TempDir
    Path temp;

    @Test
    void loadsStringAndArrayBindings() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"app.model.select\":\"ctrl+p\",\"app.editor.external\":[\"ctrl+g\"]}");

        var bindings = UserKeybindings.load(file);

        assertEquals(Key.Special.CTRL_P, bindings.keysFor("app.model.select", Key.Special.CTRL_L).getFirst());
        assertEquals(Key.Special.CTRL_G, bindings.keysFor("app.editor.external").getFirst());
    }

    @Test
    void fallsBackToDefaultsForMissingOrInvalidBindings() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"app.model.select\":\"unknown\"}");

        var bindings = UserKeybindings.load(file);

        assertEquals(Key.Special.CTRL_L, bindings.keysFor("app.model.select", Key.Special.CTRL_L).getFirst());
    }

    @Test
    void createsActionRemaps() throws Exception {
        Path file = temp.resolve("keybindings.json");
        Files.writeString(file, "{\"tui.editor.cursorUp\":\"ctrl+p\"}");

        var remaps = UserKeybindings.load(file).remaps(Map.of("tui.editor.cursorUp", Key.Special.UP));

        assertEquals(Key.Special.UP, remaps.get(Key.Special.CTRL_P));
    }

    /**
     * Every key name the docs offer has to be one the parser takes. The page's own example bound ctrl+n, which the
     * parser has never accepted and the same page's key list does not mention, so following the documentation gave you
     * the default and no explanation.
     */
    @Test
    void everyKeyNameTheDocsShowCanActuallyBeBound() throws Exception {
        String docs = Files.readString(Path.of("docs/keybindings.md"));
        // Backticked prose and quoted JSON alike: the example that started this was "ctrl+n" inside a fenced block,
        // which a backticks-only pattern walks straight past -- the one place the mistake actually was.
        var named = java.util.regex.Pattern.compile("[`\"]((?:ctrl|alt|shift)\\+[a-z+-]+)[`\"]").matcher(docs);

        var rejected = new java.util.ArrayList<String>();
        while (named.find()) {
            String name = named.group(1);
            if (!RESERVED.contains(name) && !isBindable(name))
                rejected.add(name);
        }

        assertTrue(rejected.isEmpty(), "the docs name keys pijota cannot bind: " + rejected);
    }

    /** The keys the page explains you cannot bind, because the terminal already means something by them. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of("ctrl+m", "ctrl+h", "ctrl+c", "ctrl+d",
                                    "ctrl+z");

    private static boolean isBindable(String name) throws Exception {
        var parse = UserKeybindings.class.getDeclaredMethod("parseKey", String.class);
        parse.setAccessible(true);
        return ((java.util.Optional<?>) parse.invoke(null, name)).isPresent();
    }

    /** A name pijota cannot bind leaves the action on its default rather than being dropped without a word. */
    @Test
    void anUnbindableNameLeavesTheDefaultInPlace(@TempDir Path dir) throws Exception {
        var file = dir.resolve("keybindings.json");
        Files.writeString(file, "{\"app.model.select\": [\"ctrl+n\"]}");

        var keys = UserKeybindings.load(file);

        assertEquals(java.util.List.of(Key.Special.CTRL_L), keys.keysFor("app.model.select", Key.Special.CTRL_L),
                                        "an unusable binding must not silently unbind the action");
    }
}
