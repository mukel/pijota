package org.pijota.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.pijota.testkit.ToolRunner.run;
import static org.junit.jupiter.api.Assertions.*;

class TodoToolTest {

    @Test
    void nameIsTodowrite() {
        var tool = new TodoTool();
        assertEquals("todo", tool.name());
    }

    @Test
    void descriptionIsNotEmpty() {
        var tool = new TodoTool();
        assertNotNull(tool.description());
        assertTrue(tool.description().contains("todo"));
    }

    @Test
    void executeWithTodosRendersList() {
        var tool = new TodoTool();
        var todos = List.of(Map.of("content", "task A", "status", "completed", "priority", "high"),
                                        Map.of("content", "task B", "status", "in_progress", "priority", "medium"),
                                        Map.of("content", "task C", "status", "pending", "priority", "low"));
        var result = run(tool, Map.of("todos", todos), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("[✓] task A"));
        assertTrue(result.content().contains("[~] task B"));
        assertTrue(result.content().contains("[ ] task C"));
        assertTrue(result.content().contains("3 todos (1 completed)"));
    }

    @Test
    void executeWithCancelledTodo() {
        var tool = new TodoTool();
        var todos = List.of(Map.of("content", "cancelled task", "status", "cancelled", "priority", "low"));
        var result = run(tool, Map.of("todos", todos), "/cwd");
        assertTrue(result.content().contains("[−]"));
    }

    @Test
    void executeWithInvalidTodosReturnsError() {
        var tool = new TodoTool();
        var result = run(tool, Map.of("todos", "not-a-list"), "/cwd");
        assertTrue(result.isError());
        assertTrue(result.content().contains("must be an array"));
    }

    @Test
    void executeWithEmptyListReportsZero() {
        var tool = new TodoTool();
        var result = run(tool, Map.of("todos", List.of()), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("0 todos (0 completed)"));
    }

    @Test
    void executeSkipsNonMapItems() {
        var tool = new TodoTool();
        var todos = List.of(Map.of("content", "a", "status", "completed", "priority", "high"), "not-a-map");
        var result = run(tool, Map.of("todos", todos), "/cwd");
        assertFalse(result.isError());
        assertTrue(result.content().contains("1 todos (1 completed)"));
    }

    @Test
    void renderResultReturnsErrorContent() {
        var tool = new TodoTool();
        var errorResult = Tool.ToolResult.error("something wrong");
        var view = tool.renderResult(new Tool.Args(Map.of()), errorResult, "/cwd");
        assertEquals("something wrong", view.summary());
    }

    @Test
    void renderResultSplitsLastLineAsSummary() {
        var tool = new TodoTool();
        var result = Tool.ToolResult.success("[✓] task A\n[ ] task B\n2 todos (1 completed)");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/cwd");
        assertEquals("2 todos (1 completed)", view.summary());
        assertEquals(2, view.body().size());
        assertTrue(view.body().get(0).contains("task A"));
    }

    @Test
    void renderResultHandlesEmptyContent() {
        var tool = new TodoTool();
        var result = Tool.ToolResult.success("");
        var view = tool.renderResult(new Tool.Args(Map.of()), result, "/cwd");
        assertEquals("", view.summary());
    }

    @Test
    void defaultEnabledIsTrue() {
        var tool = new TodoTool();
        assertTrue(tool.defaultEnabled());
    }

    @Test
    void updateConsumerReceivesTheFullParsedList() {
        var captured = new AtomicReference<List<TodoTool.Todo>>();
        var tool = new TodoTool(captured::set);
        run(tool, Map.of("todos", List.of(Map.of("content", "A", "status", "in_progress", "priority", "high"),
                                        Map.of("content", "B", "status", "pending", "priority", "low"))), "/cwd");

        assertNotNull(captured.get());
        assertEquals(2, captured.get().size());
        assertEquals("A", captured.get().get(0).content());
        assertEquals("in_progress", captured.get().get(0).status());
        assertEquals("high", captured.get().get(0).priority());
    }
}
