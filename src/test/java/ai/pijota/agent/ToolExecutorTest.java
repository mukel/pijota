package ai.pijota.agent;

import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    private static Tool fastTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("ok from " + name));
            }
        };
    }

    private static Tool sequentialTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "sequential tool";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public ExecutionMode executionMode() {
                return ExecutionMode.SEQUENTIAL;
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("seq result"));
            }
        };
    }

    @Test
    void executeEmptyRequestsReturnsCompletedFuture() {
        var tools = new ToolRegistry();
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        var future = executor.execute(List.of());
        assertTrue(future.isDone());
    }

    @Test
    void executeSingleToolAddsResultToHistory() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("read"));
        var history = new MessageHistory();
        var events = new java.util.ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("call-1", "read", "{\"file\":\"x.txt\"}"))).get();
        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertEquals("call-1", msg.toolCallId());
        assertEquals("read", msg.toolName());
        assertTrue(msg.content().contains("ok from read"));
    }

    @Test
    void executeSingleToolEmitsEvents() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("read"));
        var history = new MessageHistory();
        var events = new java.util.ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("call-1", "read", "{\"file\":\"x.txt\"}"))).get();

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResult));
        var start = (AgentEvent.ToolStart) events.stream().filter(e -> e instanceof AgentEvent.ToolStart).findFirst()
                                        .get();
        assertEquals("read", start.toolName());
        assertEquals("call-1", start.toolCallId());
    }

    @Test
    void executeParallelMultipleTools() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("read"));
        tools.register(fastTool("glob"));
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "read", "{}"), new ToolCall("c2", "glob", "{}"))).get();

        assertEquals(2, history.snapshot().size());
    }

    @Test
    void executeSequentialWhenToolHasSequentialMode() throws Exception {
        var tools = new ToolRegistry();
        tools.register(sequentialTool("transcendence"));
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "transcendence", "{}"), new ToolCall("c2", "transcendence", "{}")))
                                        .get();

        assertEquals(2, history.snapshot().size());
    }

    @Test
    void abortedBeforeExecutionStopsSequential() throws Exception {
        var tools = new ToolRegistry();
        tools.register(sequentialTool("transcendence"));
        var history = new MessageHistory();
        var aborted = new AtomicBoolean(true);
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, aborted);

        executor.execute(List.of(new ToolCall("c1", "transcendence", "{}"), new ToolCall("c2", "transcendence", "{}")))
                                        .get();
        assertTrue(history.snapshot().isEmpty());
    }

    @Test
    void unknownToolReportsError() throws Exception {
        var tools = new ToolRegistry();
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "nonexistent", "{}"))).get();
        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertTrue(msg.content().contains("Unknown tool"));
    }

    @Test
    void toolResultIncludesDetails() throws Exception {
        var tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override
            public String name() {
                return "read";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                return CompletableFuture.completedFuture(Tool.ToolResult.success("ok", Map.of("lines", 42)));
            }
        });
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "read", "{}"))).get();
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertFalse(msg.isError());
        assertEquals(Map.of("lines", 42), msg.details());
    }

    @Test
    void toolErrorDuringExecutionReportsErrorInMessage() throws Exception {
        var tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public String description() {
                return "test";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Map<String, Object> args, String cwd) {
                var f = new CompletableFuture<Tool.ToolResult>();
                f.completeExceptionally(new RuntimeException("boom"));
                return f;
            }
        });
        var history = new MessageHistory();
        var events = new java.util.ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "failing", "{}"))).get();
        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertTrue(msg.content().contains("boom"));
    }
}
