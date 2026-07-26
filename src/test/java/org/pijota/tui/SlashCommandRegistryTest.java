package org.pijota.tui;

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
    void ranksExactAndPrefixMatchesBeforeFuzzyMatches() {
        var matches = registry.complete("/session");

        assertEquals("/session", matches.getFirst());
        assertTrue(matches.indexOf("/sessions") > matches.indexOf("/session"));
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
}
