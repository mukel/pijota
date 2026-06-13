package ai.pijota.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkingAnimationTest {

    @Test
    void defaultsToDotsStyle() {
        assertEquals("dots", new WorkingAnimation().style());
    }

    @Test
    void advanceCyclesThroughFramesAndWraps() {
        var a = new WorkingAnimation();
        String first = a.frame();
        // Advance a full cycle of the longest style isn't needed; just confirm motion then wrap-around.
        a.advance();
        assertNotEquals(first, a.frame(), "frame should change after advancing");
        // Advance enough to wrap back to the starting frame of the 8-frame dots style.
        for (int i = 0; i < 7; i++)
            a.advance();
        assertEquals(first, a.frame(), "frame index should wrap modulo the style length");
    }

    @Test
    void setSelectsKnownStyleAndIgnoresUnknown() {
        var a = new WorkingAnimation();
        a.set("spinner");
        assertEquals("spinner", a.style());
        a.set("does-not-exist");
        assertEquals("spinner", a.style(), "unknown style is ignored");
        a.set(null);
        assertEquals("spinner", a.style(), "null is ignored");
    }

    @Test
    void toggleCyclesDeterministicallyAndReturnsNewStyle() {
        var a = new WorkingAnimation();
        assertEquals("dots", a.style());
        assertEquals("moon", a.toggle());
        assertEquals("spinner", a.toggle());
        assertEquals("dots", a.toggle(), "toggle wraps back to the first style");
    }

    @Test
    void frameMatchesSelectedStyle() {
        var a = new WorkingAnimation();
        a.set("spinner");
        assertEquals("⠋", a.frame());
    }
}
