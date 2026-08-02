package org.pijota.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ToolTest {

    @Test
    void toolResultSuccessStoresContent() {
        var result = Tool.ToolResult.success("done");
        assertEquals("done", result.content());
        assertFalse(result.isError());
        assertTrue(result.details().isEmpty());
    }

    @Test
    void toolResultSuccessWithDetails() {
        var result = Tool.ToolResult.success("done", Map.of("lines", 42));
        assertEquals("done", result.content());
        assertEquals(Map.of("lines", 42), result.details());
    }

    @Test
    void toolResultSuccessNullDetailsCoercesToEmpty() {
        var result = Tool.ToolResult.success("done", null);
        assertTrue(result.details().isEmpty());
    }

    @Test
    void toolResultErrorStoresContent() {
        var result = Tool.ToolResult.error("failed");
        assertEquals("failed", result.content());
        assertTrue(result.isError());
    }

    @Test
    void toolDefinition_toTool_propagatesNameDescriptionSchema() {
        var def = new Tool.ToolDefinition("mytool", "does stuff", Map.of("prop", "value"), (id, args,
                                        cwd) -> CompletableFuture.completedFuture(Tool.ToolResult.success("ok")));
        var tool = def.toTool();
        assertEquals("mytool", tool.name());
        assertEquals("does stuff", tool.description());
        assertEquals(Map.of("prop", "value"), tool.schema());
    }

    @Test
    void toolDefinition_toTool_defaultEnabledTrueByDefault() {
        var def = new Tool.ToolDefinition("t", "d", Map.of(), (id, args, cwd) -> CompletableFuture
                                        .completedFuture(Tool.ToolResult.success("ok")));
        assertTrue(def.toTool().defaultEnabled());
    }

    @Test
    void toolDefinition_toTool_disabledWhenSet() {
        var def = new Tool.ToolDefinition("t", "d", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")),
                                        false);
        assertFalse(def.toTool().defaultEnabled());
    }

    @Test
    void toolDefinition_toTool_executesWithGet() throws Exception {
        var def = new Tool.ToolDefinition("t", "d", Map.of(), (id, args, cwd) -> CompletableFuture
                                        .completedFuture(Tool.ToolResult.success("done")));
        var tool = def.toTool();
        var result = tool.execute("call-id", new Tool.Args(Map.of())).get();
        assertEquals("done", result.content());
    }

    @Test
    void toolDefinition_toTool_executesWithCwd() throws Exception {
        var def = new Tool.ToolDefinition("t", "d", Map.of(), (id, args, cwd) -> CompletableFuture
                                        .completedFuture(Tool.ToolResult.success(cwd)));
        var tool = def.toTool();
        var result = tool.execute("call-id", new Tool.Args(Map.of()), "/home/user").get();
        assertEquals("/home/user", result.content());
    }

    @Test
    void toolDefinition_toTool_withRendererDelegates() {
        var view = new Tool.ToolResultView("summary", List.of("line1", "line2"));
        Tool.ResultRenderer renderer = (args, result, cwd) -> view;
        var def = new Tool.ToolDefinition("t", "d", Map.of(),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")),
                                        renderer);
        var tool = def.toTool();
        var rendered = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.success("ignored"), "/cwd");
        assertEquals("summary", rendered.summary());
        assertEquals(List.of("line1", "line2"), rendered.body());
    }

    @Test
    void toolDefinition_toTool_withoutRendererUsesDefault() {
        var def = new Tool.ToolDefinition("t", "d", Map.of(), (id, args, cwd) -> CompletableFuture
                                        .completedFuture(Tool.ToolResult.success("ok")));
        var tool = def.toTool();
        var rendered = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.success("first line\nsecond line"),
                                        "/cwd");
        assertFalse(rendered.summary().contains("\n"), "default renderer should use first line only");
    }

    @Test
    void defaultRenderResult_firstLine() {
        var tool = new FakeTool("test", "desc", Map.of());
        var view = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.success("line1\nline2\nline3"), "/cwd");
        assertEquals("line1", view.summary());
    }

    @Test
    void defaultRenderResult_errorUsesFullContent() {
        var tool = new FakeTool("test", "desc", Map.of());
        var view = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.error("error message\nline2"), "/cwd");
        assertEquals("error message\nline2", view.summary());
    }

    @Test
    void defaultRenderResult_nullContentReturnsEmpty() {
        var tool = new FakeTool("test", "desc", Map.of());
        var view = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.success(null), "/cwd");
        assertEquals("", view.summary());
    }

    @Test
    void defaultRenderResult_blankContentReturnsEmpty() {
        var tool = new FakeTool("test", "desc", Map.of());
        var view = tool.renderResult(new Tool.Args(Map.of()), Tool.ToolResult.success("   "), "/cwd");
        assertEquals("", view.summary());
    }

    @Test
    void executionMode_defaultsToParallel() {
        var tool = new FakeTool("test", "desc", Map.of());
        assertEquals(Tool.ExecutionMode.PARALLEL, tool.executionMode());
    }

    @Test
    void toolResultView_ofSummaryOnly() {
        var view = Tool.ToolResultView.of("done");
        assertEquals("done", view.summary());
        assertTrue(view.body().isEmpty());
    }

    @Test
    void toolResultView_ofSummaryAndBody() {
        var view = Tool.ToolResultView.of("summary", List.of("line1", "line2"));
        assertEquals("summary", view.summary());
        assertEquals(List.of("line1", "line2"), view.body());
    }

    @Test
    void toolInfoStoresNameAndDescription() {
        var info = new Tool.ToolInfo("read", "Reads a file");
        assertEquals("read", info.name());
        assertEquals("Reads a file", info.description());
    }

    private static class FakeTool implements Tool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;

        FakeTool(String name, String description, Map<String, Object> schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> schema() {
            return schema;
        }

        @Override
        public CompletableFuture<Tool.ToolResult> execute(String toolCallId, Tool.Args arguments, String sessionCwd) {
            return CompletableFuture.completedFuture(Tool.ToolResult.success("ok"));
        }
    }
}
