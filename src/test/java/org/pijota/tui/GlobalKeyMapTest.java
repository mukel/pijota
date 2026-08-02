package org.pijota.tui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GlobalKeyMapTest {

    @Test
    void dispatchRunsTheBoundActionForTheKey() {
        var fired = new AtomicReference<String>();
        var map = new GlobalKeyMap().bind(Key.Special.CTRL_G, () -> fired.set("editor")).bind(Key.Special.CTRL_L,
                                        () -> fired.set("model"));

        assertTrue(map.dispatch(Key.Special.CTRL_L));
        assertEquals("model", fired.get());
    }

    @Test
    void dispatchReturnsFalseForUnboundKey() {
        var fired = new AtomicReference<String>();
        var map = new GlobalKeyMap().bind(Key.Special.CTRL_G, () -> fired.set("editor"));

        assertFalse(map.dispatch(Key.Special.CTRL_C));
        assertNull(fired.get());
    }

    @Test
    void distinctKeysRouteIndependently() {
        var fired = new AtomicReference<String>();
        var map = new GlobalKeyMap().bind(Key.Special.CTRL_G, () -> fired.set("g"))
                                        .bind(Key.Special.SHIFT_TAB, () -> fired.set("tab"))
                                        .bind(Key.Special.CTRL_P, () -> fired.set("palette"));

        assertTrue(map.dispatch(Key.Special.SHIFT_TAB));
        assertEquals("tab", fired.get());
        assertTrue(map.dispatch(Key.Special.CTRL_G));
        assertEquals("g", fired.get());
    }
}
