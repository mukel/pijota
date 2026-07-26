package org.pijota.extensions;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionResultTest {

    @Test
    void blockStoresReason() {
        var block = new ExtensionResult.Block("not allowed");
        assertEquals("not allowed", block.reason());
    }

    @Test
    void inputResultStoresTextAndConsumedFlag() {
        var result = new ExtensionResult.InputResult("filtered text", true);
        assertEquals("filtered text", result.text());
        assertTrue(result.consumed());
    }

    @Test
    void inputResultNotConsumed() {
        var result = new ExtensionResult.InputResult("pass through", false);
        assertFalse(result.consumed());
    }

    @Test
    void userBashAllow() {
        var result = ExtensionResult.UserBashResult.allow();
        assertFalse(result.blocked());
        assertNull(result.reason());
        assertNull(result.replacementCommand());
    }

    @Test
    void userBashBlock() {
        var result = ExtensionResult.UserBashResult.block("dangerous");
        assertTrue(result.blocked());
        assertEquals("dangerous", result.reason());
    }

    @Test
    void userBashReplace() {
        var result = ExtensionResult.UserBashResult.replace("safe-command");
        assertFalse(result.blocked());
        assertEquals("safe-command", result.replacementCommand());
    }

    @Test
    void bashToolCallAllow() {
        var result = ExtensionResult.BashToolCallResult.allow();
        assertFalse(result.blocked());
        assertNull(result.reason());
    }

    @Test
    void bashToolCallBlock() {
        var result = ExtensionResult.BashToolCallResult.block("restricted");
        assertTrue(result.blocked());
        assertEquals("restricted", result.reason());
    }

    @Test
    void messageRenderStoresLines() {
        var render = new ExtensionResult.MessageRender(List.of("line1", "line2"));
        assertEquals(List.of("line1", "line2"), render.lines());
    }

    @Test
    void messageRenderNullLinesCoercedToEmpty() {
        var render = new ExtensionResult.MessageRender(null);
        assertTrue(render.lines().isEmpty());
    }

    @Test
    void messageRenderLinesIsImmutable() {
        var mutable = new ArrayList<>(List.of("a"));
        var render = new ExtensionResult.MessageRender(mutable);
        assertThrows(UnsupportedOperationException.class, () -> render.lines().add("b"));
    }

    @Test
    void blockEquality() {
        assertEquals(new ExtensionResult.Block("x"), new ExtensionResult.Block("x"));
    }

    @Test
    void userBashResultEquality() {
        assertEquals(ExtensionResult.UserBashResult.allow(), ExtensionResult.UserBashResult.allow());
        assertNotEquals(ExtensionResult.UserBashResult.allow(), ExtensionResult.UserBashResult.block("x"));
    }
}
