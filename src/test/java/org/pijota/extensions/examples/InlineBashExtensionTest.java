package org.pijota.extensions.examples;

import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.extensions.testkit.FakeUiContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InlineBashExtensionTest {

    @Test
    void name() {
        var ext = new InlineBashExtension();
        assertEquals("inline-bash", ext.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var ext = new InlineBashExtension();
        assertNotNull(ext.description());
    }

    @Test
    void initRegistersInputHandler() {
        var api = new FakeExtensionAPI();
        var ext = new InlineBashExtension();
        ext.init(api);
        assertEquals(1, api.listeners(ExtensionEvent.Input.class).size());
    }

    @Test
    void expandsInlineBashCommand() {
        var api = new FakeExtensionAPI();
        var ext = new InlineBashExtension();
        ext.init(api);
        var result = api.filterInput("hello !{echo world}");
        assertTrue(result.text().contains("world"));
        assertFalse(result.consumed());
    }

    @Test
    void leavesLeadingExclamationAlone() {
        var api = new FakeExtensionAPI();
        var ext = new InlineBashExtension();
        ext.init(api);
        var result = api.filterInput("!echo world");
        assertEquals("!echo world", result.text());
    }

    @Test
    void returnsOriginalWhenNoInlineCommands() {
        var api = new FakeExtensionAPI();
        var ext = new InlineBashExtension();
        ext.init(api);
        var result = api.filterInput("hello world");
        assertEquals("hello world", result.text());
    }

    @Test
    void reportsErrorForFailingCommand() {
        var api = new FakeExtensionAPI();
        var ext = new InlineBashExtension();
        ext.init(api);
        var result = api.filterInput("!{/nonexistent-binary-xyz}");
        assertFalse(result.text().contains("hello") || result.text().equals("!{/nonexistent-binary-xyz}"));
        assertTrue(result.text().contains("command not found") || result.text().contains("[error:")
                                        || result.text().contains("No such file"),
                                        "Expected error text but got: " + result.text());
    }

    @Test
    void setsStatusWhenUiAvailable() {
        var runtime = new FakeExtensionRuntime();
        var ui = new FakeUiContext();
        var api = new FakeExtensionAPI(runtime);
        api.setUi(ui);
        var ext = new InlineBashExtension();
        ext.init(api);
        api.filterInput("hello !{echo world}");
        assertTrue(runtime.keyedStatuses().containsKey("inline-bash"));
    }
}
