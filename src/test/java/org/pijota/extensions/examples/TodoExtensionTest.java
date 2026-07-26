package org.pijota.extensions.examples;

import org.pijota.extensions.testkit.FakeExtensionAPI;
import org.pijota.extensions.testkit.FakeExtensionRuntime;
import org.pijota.tools.TodoTool.Todo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoExtensionTest {

    @Test
    void todoIsOffByDefault() {
        // opt-in (--todo): the extension declares itself off; ExtensionRegistry gates registration on that default.
        assertFalse(new TodoExtension().enabledByDefault(), "todo must be opt-in (--todo)");
    }

    @Test
    void todoAndPanelRegisteredWhenFlagEnabled() {
        var runtime = new FakeExtensionRuntime();
        runtime.setFlag("todo", "true");
        var api = new FakeExtensionAPI(runtime);
        new TodoExtension().init(api);
        assertNotNull(api.tool("todo"), "todo must be registered with --todo");
        assertFalse(api.statusLineSuppliers().isEmpty(), "a live todo panel is registered with --todo");
    }

    // ---- live panel ----

    @Test
    void panelIsEmptyWithNoTodos() {
        assertTrue(TodoExtension.renderPanel(List.of()).isEmpty());
    }

    @Test
    void panelIsHiddenOnceEverythingIsFinished() {
        var done = List.of(new Todo("a", "completed", "low"), new Todo("b", "cancelled", "low"));
        assertTrue(TodoExtension.renderPanel(done).isEmpty(), "a finished list shouldn't clutter the footer");
    }

    @Test
    void panelShowsTallyHeaderAndEachTodo() {
        var list = List.of(new Todo("write parser", "in_progress", "high"), new Todo("add tests", "pending", "medium"),
                                        new Todo("read spec", "completed", "low"));
        var panel = TodoExtension.renderPanel(list);

        assertEquals("📋 todos · 1/3", panel.get(0));
        assertTrue(panel.stream().anyMatch(l -> l.contains("[~] write parser")));
        assertTrue(panel.stream().anyMatch(l -> l.contains("[ ] add tests")));
        assertTrue(panel.stream().anyMatch(l -> l.contains("[✓] read spec")));
    }

    @Test
    void panelSummarizesOverflowBeyondTheRowCap() {
        var many = new ArrayList<Todo>();
        for (int i = 0; i < 12; i++)
            many.add(new Todo("t" + i, "pending", "low"));
        var panel = TodoExtension.renderPanel(many);
        assertTrue(panel.getLast().contains("+4 more"), panel.getLast()); // header + 8 rows shown, 4 summarized
    }
}
