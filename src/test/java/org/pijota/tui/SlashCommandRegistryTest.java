package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        String source = Files.readString(Path.of("src/main/java/org/pijota/tui/TuiCommandController.java"),
                                        StandardCharsets.UTF_8);
        // One entry per case label, each holding all the names that label answers to. Grouping them is what makes an
        // alias an alias: "/quit", "/q", "/exit" is one feature, and listing any one of the three makes it findable.
        var cases = new ArrayList<Set<String>>();
        var m = Pattern.compile("case ((?:\"/[a-z?-]+\"(?:, )?)+) ->").matcher(source);
        while (m.find()) {
            var names = new TreeSet<String>();
            var each = Pattern.compile("\"(/[a-z?-]+)\"").matcher(m.group(1));
            while (each.find())
                names.add(each.group(1));
            cases.add(names);
        }
        assertFalse(cases.isEmpty(), "no cases parsed; the dispatcher's shape changed");

        var listed = registry.all().stream().map(item -> item.name().split(" ")[0])
                                        .collect(Collectors.toCollection(TreeSet::new));

        var undiscoverable = cases.stream().filter(names -> names.stream().noneMatch(listed::contains)).toList();
        assertTrue(undiscoverable.isEmpty(), "handled but in no completion list, /help or palette: " + undiscoverable);

        var dispatched = cases.stream().flatMap(Set::stream).collect(Collectors.toCollection(TreeSet::new));
        var dead = new TreeSet<>(listed);
        dead.removeAll(dispatched);
        assertTrue(dead.isEmpty(), "advertised but nothing handles them: " + dead);
    }

    @Test
    void matchPositionsEmptyWhenNoMatchOrBlankQuery() {
        assertEquals(0, SlashCommandRegistry.matchPositions("xyz", "model").length, "no match → no positions");
        assertEquals(0, SlashCommandRegistry.matchPositions("", "model").length, "blank query → no highlight");
        assertEquals(0, SlashCommandRegistry.matchPositions("mo", null).length, "null target → no positions");
    }

    /**
     * One page, one column. Each group of /help is its own panel and each used to size its label column to its own
     * widest command, so the descriptions began at column 20 under "chat", 29 under "session" and 12 under "account",
     * and the left edge jumped around while scrolling.
     */
    @Test
    void everyCommandDescriptionStartsInTheSameColumn() {
        var columns = new TreeSet<Integer>();
        for (String line : new SlashCommandRegistry().helpLines()) {
            if (!line.startsWith("  /"))
                continue;
            var m = Pattern.compile("^ {2}\\S.*?  +(\\S)").matcher(line);
            if (m.find())
                columns.add(m.start(1));
        }

        assertEquals(1, columns.size(), "descriptions start in " + columns.size() + " different columns: " + columns);
    }

}
