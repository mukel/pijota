package org.pijota.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThinkingLevelTest {
    @Test
    void fromStringParsesLabels() {
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.fromString("off"));
        assertEquals(ThinkingLevel.MINIMAL, ThinkingLevel.fromString("minimal"));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromString("low"));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString("medium"));
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromString("high"));
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.fromString("xhigh"));
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.fromString("max"));
    }

    @Test
    void fromStringIsCaseInsensitiveAndTrims() {
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.fromString("  HIGH  "));
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.fromString("Low"));
    }

    @Test
    void fromStringFallsBackToMediumForUnknownInput() {
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString("garbage"));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString(""));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString(null));
    }

    @Test
    void nextCyclesThroughLevels() {
        assertEquals(ThinkingLevel.MINIMAL, ThinkingLevel.OFF.next());
        assertEquals(ThinkingLevel.LOW, ThinkingLevel.MINIMAL.next());
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.LOW.next());
        assertEquals(ThinkingLevel.HIGH, ThinkingLevel.MEDIUM.next());
        assertEquals(ThinkingLevel.XHIGH, ThinkingLevel.HIGH.next());
        assertEquals(ThinkingLevel.MAX, ThinkingLevel.XHIGH.next());
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.MAX.next());
    }

    @Test
    void budgetsIncreaseWithLevel() {
        assertTrue(ThinkingLevel.OFF.budgetTokens() < ThinkingLevel.MINIMAL.budgetTokens());
        assertTrue(ThinkingLevel.LOW.budgetTokens() < ThinkingLevel.MEDIUM.budgetTokens());
        assertTrue(ThinkingLevel.MEDIUM.budgetTokens() < ThinkingLevel.HIGH.budgetTokens());
        assertTrue(ThinkingLevel.HIGH.budgetTokens() < ThinkingLevel.XHIGH.budgetTokens());
        assertTrue(ThinkingLevel.XHIGH.budgetTokens() < ThinkingLevel.MAX.budgetTokens());
    }

    /**
     * The accepted spellings are derived from the enum, not restated. {@code ArgParser} kept its own copy of the list
     * for the {@code --thinking} error message, so adding a level would have left the flag rejecting it.
     */
    @Test
    void theAcceptedNamesAreTheLevelsThemselves() {
        assertEquals(java.util.Arrays.stream(ThinkingLevel.values()).map(ThinkingLevel::label).toList(),
                                        ThinkingLevel.names());
        for (var level : ThinkingLevel.values())
            assertTrue(ThinkingLevel.isKnown(level.label()), level + " is not recognised by its own name");
    }

    /**
     * {@code fromString} answers MEDIUM for a blank and for a typo alike -- the right default, the wrong silence.
     * {@code isKnown} is what lets a caller holding the user's own words say it did not recognise them, which is why
     * {@code "thinking_level": "hgh"} in settings.json was coerced to medium without a word for so long.
     */
    @Test
    void aTypoIsDistinguishableFromAChoiceEvenThoughBothDefaultToMedium() {
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString("hgh"));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString(""));
        assertEquals(ThinkingLevel.MEDIUM, ThinkingLevel.fromString(null));

        assertFalse(ThinkingLevel.isKnown("hgh"), "a typo is not a level");
        assertFalse(ThinkingLevel.isKnown(""), "and neither is empty");
        assertFalse(ThinkingLevel.isKnown(null));
        assertTrue(ThinkingLevel.isKnown("MEDIUM"), "case and padding are the user's business, not an error");
        assertTrue(ThinkingLevel.isKnown("  max  "));
    }

    /** One wording, so a typo reads the same whether it came from the flag or the config file. */
    @Test
    void aTypoIsReportedTheSameWayFromEitherSource() {
        assertTrue(ThinkingLevel.unknownValueMessage("--thinking", "hgh").contains("unknown --thinking 'hgh'"));
        assertTrue(ThinkingLevel.unknownValueMessage("thinking_level", "hgh").contains("unknown thinking_level 'hgh'"));
        for (String message : new String[] {ThinkingLevel.unknownValueMessage("--thinking", "x"),
                ThinkingLevel.unknownValueMessage("thinking_level", "x")}) {
            assertTrue(message.contains("off, minimal, low, medium, high, xhigh, max"), message);
            assertTrue(message.endsWith("using medium"), message);
        }
    }
}
