package org.pijota.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathDisplayTest {

    @Test
    void fromConfigParsesCaseInsensitivelyAndTrims() {
        assertEquals(PathDisplay.FULL, PathDisplay.fromConfig("full"));
        assertEquals(PathDisplay.RELATIVE, PathDisplay.fromConfig("RELATIVE"));
        assertEquals(PathDisplay.COMPACT, PathDisplay.fromConfig("  Compact  "));
        assertEquals(PathDisplay.NAME, PathDisplay.fromConfig("name"));
    }

    @Test
    void fromConfigDefaultsToCompactForNullBlankOrUnknown() {
        assertEquals(PathDisplay.COMPACT, PathDisplay.fromConfig(null));
        assertEquals(PathDisplay.COMPACT, PathDisplay.fromConfig(""));
        assertEquals(PathDisplay.COMPACT, PathDisplay.fromConfig("   "));
        assertEquals(PathDisplay.COMPACT, PathDisplay.fromConfig("nonsense"));
    }

    @Test
    void configRoundTripsForEveryMode() {
        for (PathDisplay m : PathDisplay.values())
            assertEquals(m, PathDisplay.fromConfig(m.config()));
        assertEquals("full", PathDisplay.FULL.config());
        assertEquals("name", PathDisplay.NAME.config());
    }

    @Test
    void nextCyclesInDeclarationOrderAndWraps() {
        assertEquals(PathDisplay.RELATIVE, PathDisplay.FULL.next());
        assertEquals(PathDisplay.COMPACT, PathDisplay.RELATIVE.next());
        assertEquals(PathDisplay.NAME, PathDisplay.COMPACT.next());
        assertEquals(PathDisplay.FULL, PathDisplay.NAME.next(), "wraps back to the first");
    }
}
