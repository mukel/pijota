package ai.pijota.tui;

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

        assertEquals("/load", matches.getFirst().name());
        assertFalse(matches.getFirst().description().isBlank());
    }

    @Test
    void helpTextContainsStableCommandGroupsAndKeys() {
        var help = registry.helpText();

        assertTrue(help.contains("chat"));
        assertTrue(help.contains("session"));
        assertTrue(help.contains("workspace"));
        assertTrue(help.contains("Enter submit"));
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
}
