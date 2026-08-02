package org.pijota.extensions;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionEventTest {

    @Test
    void nameMethodReturnsCorrectNames() {
        assertEquals("session_start", new ExtensionEvent.SessionStart(Path.of("/tmp")).name());
        assertEquals("turn_start", new ExtensionEvent.TurnStart(1).name());
        assertEquals("turn_end", new ExtensionEvent.TurnEnd(2).name());
        assertEquals("agent_start", new ExtensionEvent.AgentStart().name());
        assertEquals("agent_end", new ExtensionEvent.AgentEnd().name());
    }

    @Test
    void sessionStartStoresPath() {
        var event = new ExtensionEvent.SessionStart(Path.of("/tmp/session"));
        assertEquals(Path.of("/tmp/session"), event.sessionPath());
    }

    @Test
    void turnStartStoresNumber() {
        var event = new ExtensionEvent.TurnStart(5);
        assertEquals(5, event.turnNumber());
    }

    @Test
    void sessionBeforeCompactHasDefault() {
        var event = new ExtensionEvent.SessionBeforeCompact(false, null);
        assertFalse(event.cancel());
        assertNull(event.instructions());
    }

    @Test
    void sessionBeforeCompactCancels() {
        var event = new ExtensionEvent.SessionBeforeCompact(true, "memory limit");
        assertTrue(event.cancel());
        assertEquals("memory limit", event.instructions());
    }

    @Test
    void inputEventNameDependsOnHandled() {
        assertEquals("input_handled", new ExtensionEvent.Input("text", true).name());
        assertEquals("input", new ExtensionEvent.Input("text", false).name());
    }

    @Test
    void inputStoresTextAndHandledFlag() {
        var event = new ExtensionEvent.Input("hello", true);
        assertEquals("hello", event.text());
        assertTrue(event.handled());
    }

    @Test
    void sealedHierarchyCoversAllCases() {
        assertTrue(new ExtensionEvent.SessionStart(Path.of("/tmp")) instanceof ExtensionEvent);
        assertTrue(new ExtensionEvent.TurnStart(1) instanceof ExtensionEvent);
        assertTrue(new ExtensionEvent.AgentStart() instanceof ExtensionEvent);
        assertTrue(new ExtensionEvent.Input("x", false) instanceof ExtensionEvent);
    }
}
