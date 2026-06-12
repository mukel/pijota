package ai.pijota.extensions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionContextTest {

    @Test
    void hasUIFalseWhenUIIsNull() {
        var ctx = new ExtensionContext(null, null);
        assertFalse(ctx.hasUI());
    }

    @Test
    void hasUITrueWhenUIIsNotNull() {
        UiContext ui = new UiContext() {
            @Override
            public void setStatus(String message) {}
        };
        var ctx = new ExtensionContext(ui, null);
        assertTrue(ctx.hasUI());
        assertSame(ui, ctx.ui());
    }

    @Test
    void runtimeReturnsNullWhenNotSet() {
        var ctx = new ExtensionContext(null, null);
        assertNull(ctx.runtime());
    }

    @Test
    void messagesReturnsEmptyWhenRuntimeIsNull() {
        var ctx = new ExtensionContext(null, null);
        assertTrue(ctx.messages().isEmpty());
    }

    @Test
    void compactWithNullRuntimeDoesNotThrow() {
        var ctx = new ExtensionContext(null, null);
        assertDoesNotThrow(() -> ctx.compact("instructions"));
    }

    @Test
    void shutdownWithNullRuntimeDoesNotThrow() {
        var ctx = new ExtensionContext(null, null);
        assertDoesNotThrow(() -> ctx.shutdown());
    }
}
