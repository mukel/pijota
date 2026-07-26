package org.pijota.tui;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CtrlXKeymapTest {

    @Test
    void hintIsDerivedFromBindingsInOrder() {
        var map = new CtrlXKeymap().bind('m', "model", () -> {}).bind('l', "sessions", () -> {});
        assertEquals("C-x  m:model  l:sessions", map.hint());
    }

    @Test
    void dispatchRunsBoundActionAndIsCaseInsensitive() {
        var hits = new AtomicInteger();
        var map = new CtrlXKeymap().bind('m', "model", hits::incrementAndGet);

        assertTrue(map.dispatch('m'));
        assertTrue(map.dispatch('M'));
        assertEquals(2, hits.get());
    }

    @Test
    void dispatchReturnsFalseForUnboundKey() {
        var hits = new AtomicInteger();
        var map = new CtrlXKeymap().bind('m', "model", hits::incrementAndGet);

        assertFalse(map.dispatch('z'));
        assertEquals(0, hits.get());
    }

    @Test
    void emptyKeymapHasBareHintAndDispatchesNothing() {
        var map = new CtrlXKeymap();
        assertEquals("C-x", map.hint());
        assertFalse(map.dispatch('a'));
    }
}
