package org.pijota.tui;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandRegistryTest {
    private final SlashCommandRegistry registry = new SlashCommandRegistry();

    @Test
    void completesOnlySlashPrefixedInput() {
        assertTrue(registry.complete("model").isEmpty());
        assertTrue(registry.complete(null).isEmpty());

        assertTrue(registry.complete("/mod").contains("/model"));
    }

    @Test
    void allReturnsEveryCommandUnfiltered() {
        // The command palette (Ctrl+P) sources its commands from all(); completeWithDescriptions("") returns nothing
        // because "" fails the slash-prefix guard, which used to leave the palette with zero commands.
        var all = registry.all();

        assertTrue(all.size() > 20, "expected the full command set, got " + all.size());
        assertTrue(all.stream().anyMatch(c -> c.name().equals("/help")));
        assertTrue(all.stream().anyMatch(c -> c.name().equals("/theme")));
        assertTrue(registry.completeWithDescriptions("").isEmpty(), "empty query still returns nothing (guard intact)");
    }

    @Test
    void descriptionsAreReturnedWithCompletionItems() {
        var matches = registry.completeWithDescriptions("/load");

        assertEquals("/load <id|path>", matches.getFirst().name());
        assertFalse(matches.getFirst().description().isBlank());
    }

    @Test
    void helpTextContainsStableCommandGroupsAndKeys() {
        var help = registry.helpText();

        assertTrue(help.contains("chat"));
        assertTrue(help.contains("session"));
        assertTrue(help.contains("workspace"));
        assertTrue(help.contains("/model"));
    }

    @Test
    void helpLinesContainsSameContentAsHelpText() {
        var lines = registry.helpLines();
        var help = registry.helpText();

        assertFalse(lines.isEmpty());
        assertTrue(help.contains(lines.getFirst()));
        for (String line : lines) {
            assertTrue(help.contains(line.stripTrailing()), "missing: " + line);
        }
    }

    // ---- completion ranking (fuzzyMatch / matchScore) ----

    @Test
    void fuzzyMatchAcceptsSubsequencesAndSubstringsButNotMisses() {
        assertTrue(SlashCommandRegistry.fuzzyMatch("", "anything"), "empty query matches everything");
        assertTrue(SlashCommandRegistry.fuzzyMatch("mdl", "model"), "subsequence m-d-l");
        assertTrue(SlashCommandRegistry.fuzzyMatch("ode", "model"), "subsequence o-d-e");
        assertFalse(SlashCommandRegistry.fuzzyMatch("xyz", "model"), "neither subsequence nor substring");
    }

    @Test
    void matchScoreRanksExactBeforePrefixBeforeFuzzyAndPenalizesGaps() {
        int exact = SlashCommandRegistry.matchScore("model", "model");
        int prefix = SlashCommandRegistry.matchScore("mod", "model");
        int contiguous = SlashCommandRegistry.matchScore("ode", "model"); // subsequence, no gaps
        int gappy = SlashCommandRegistry.matchScore("mdl", "model"); // subsequence with gaps

        assertEquals(0, exact, "exact match scores best");
        assertEquals(1, prefix, "prefix beats any fuzzy");
        assertTrue(exact < prefix && prefix < contiguous, "exact < prefix < fuzzy");
        assertTrue(gappy > contiguous, "more gaps rank worse");
    }

    @Test
    void matchPositionsPrefersSubstringThenSubsequence() {
        assertArrayEquals(new int[] {1, 2, 3}, SlashCommandRegistry.matchPositions("ode", "model"),
                                        "contiguous substring highlights the exact run");
        assertArrayEquals(new int[] {0, 2, 4}, SlashCommandRegistry.matchPositions("mdl", "model"),
                                        "no substring → greedy subsequence m(0) d(2) l(4)");
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, SlashCommandRegistry.matchPositions("MODEL", "model"),
                                        "case-insensitive; indices into the original target");
    }

    /**
     * The catalog is a hand-written list and the dispatcher is a hand-written switch, so they drift in both directions:
     * a command advertised in {@code /help} and the palette that nothing handles, or -- which is what happened -- a
     * working command in neither. {@code /bg} was dispatched and listed nowhere, and the people who need it are exactly
     * the ones the advertised Ctrl+B shortcut fails for, because inside tmux Ctrl+B is tmux's own prefix.
     */
    @Test
    void everyDispatchedCommandIsDiscoverableAndEveryListedOneDispatches() throws Exception {
        String source = java.nio.file.Files.readString(
                                        java.nio.file.Path.of("src/main/java/org/pijota/tui/TuiCommandController.java"),
                                        java.nio.charset.StandardCharsets.UTF_8);
        // One entry per case label, each holding all the names that label answers to. Grouping them is what makes an
        // alias an alias: "/quit", "/q", "/exit" is one feature, and listing any one of the three makes it findable.
        var cases = new ArrayList<java.util.Set<String>>();
        var m = java.util.regex.Pattern.compile("case ((?:\"/[a-z?-]+\"(?:, )?)+) ->").matcher(source);
        while (m.find()) {
            var names = new java.util.TreeSet<String>();
            var each = java.util.regex.Pattern.compile("\"(/[a-z?-]+)\"").matcher(m.group(1));
            while (each.find())
                names.add(each.group(1));
            cases.add(names);
        }
        assertFalse(cases.isEmpty(), "no cases parsed; the dispatcher's shape changed");

        var listed = registry.all().stream().map(item -> item.name().split(" ")[0])
                                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        var undiscoverable = cases.stream().filter(names -> names.stream().noneMatch(listed::contains)).toList();
        assertTrue(undiscoverable.isEmpty(), "handled but in no completion list, /help or palette: " + undiscoverable);

        var dispatched = cases.stream().flatMap(java.util.Set::stream)
                                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        var dead = new java.util.TreeSet<>(listed);
        dead.removeAll(dispatched);
        assertTrue(dead.isEmpty(), "advertised but nothing handles them: " + dead);
    }

    @Test
    void matchPositionsEmptyWhenNoMatchOrBlankQuery() {
        assertEquals(0, SlashCommandRegistry.matchPositions("xyz", "model").length, "no match → no positions");
        assertEquals(0, SlashCommandRegistry.matchPositions("", "model").length, "blank query → no highlight");
        assertEquals(0, SlashCommandRegistry.matchPositions("mo", null).length, "null target → no positions");
    }
}
