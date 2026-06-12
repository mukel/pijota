package ai.pijota.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmojisTest {

    @Test
    void allEmojisAreNonEmpty() {
        assertFalse(Emojis.SEE_NO_EVIL.isEmpty());
        assertFalse(Emojis.EYES.isEmpty());
        assertFalse(Emojis.WARN.isEmpty());
        assertFalse(Emojis.SHIELD.isEmpty());
        assertFalse(Emojis.UNLOCK.isEmpty());
        assertFalse(Emojis.SPARKLES.isEmpty());
        assertFalse(Emojis.PAUSE.isEmpty());
        assertFalse(Emojis.PLAY.isEmpty());
        assertFalse(Emojis.DART.isEmpty());
        assertFalse(Emojis.CLOCK.isEmpty());
        assertFalse(Emojis.ELLIPSIS.isEmpty());
    }

    @Test
    void surrogatePairEmojisAreValid() {
        assertEquals(2, Emojis.SEE_NO_EVIL.length());
        assertEquals(2, Emojis.EYES.length());

        assertTrue(Character.isHighSurrogate(Emojis.SEE_NO_EVIL.charAt(0)));
        assertTrue(Character.isLowSurrogate(Emojis.SEE_NO_EVIL.charAt(1)));
    }

    @Test
    void nonSurrogateEmojisAreValid() {
        assertEquals(1, Emojis.WARN.length());
        assertEquals(1, Emojis.SPARKLES.length());
        assertEquals(1, Emojis.PLAY.length());
    }

    @Test
    void allEmojisArePrintableNonControl() {
        for (String emoji : new String[] {Emojis.SEE_NO_EVIL, Emojis.EYES, Emojis.WARN, Emojis.SHIELD, Emojis.UNLOCK,
                Emojis.SPARKLES, Emojis.PAUSE, Emojis.PLAY, Emojis.DART, Emojis.CLOCK, Emojis.ELLIPSIS}) {
            for (int i = 0; i < emoji.length(); i++) {
                int idx = i;
                assertFalse(Character.isISOControl(emoji.charAt(idx)),
                                                () -> emoji + " contains control char at " + idx);
            }
        }
    }
}
