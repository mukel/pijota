package ai.pijota.llm;

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
        assertEquals(ThinkingLevel.OFF, ThinkingLevel.HIGH.next());
    }

    @Test
    void budgetsIncreaseWithLevel() {
        assertTrue(ThinkingLevel.OFF.budgetTokens() < ThinkingLevel.MINIMAL.budgetTokens());
        assertTrue(ThinkingLevel.LOW.budgetTokens() < ThinkingLevel.MEDIUM.budgetTokens());
        assertTrue(ThinkingLevel.MEDIUM.budgetTokens() < ThinkingLevel.HIGH.budgetTokens());
    }
}
