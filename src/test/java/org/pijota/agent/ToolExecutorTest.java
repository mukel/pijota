package org.pijota.agent;

import org.pijota.extensions.AgentContext;
import org.pijota.extensions.DeliverAs;
import org.pijota.extensions.Extension;
import org.pijota.extensions.ExtensionAPI;
import org.pijota.extensions.ExtensionEvent;
import org.pijota.extensions.ExtensionRegistry;
import org.pijota.extensions.ExtensionResult;
import org.pijota.llm.LlmClient;
import org.pijota.messages.Message;
import org.pijota.messages.ToolCall;
import org.pijota.testkit.FauxLlm;
import org.pijota.tools.Tool;
import org.pijota.tools.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutorTest {

    private static Tool fastTool(String name) {
        return new Tool.ToolDefinition(name, "test tool", Map.of(),
                                        (id, args, cwd) -> CompletableFuture.completedFuture(
                                                                        Tool.ToolResult.success("ok from " + name)))
                                        .toTool();
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
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
        var events = new ArrayList<AgentEvent>();
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
        var events = new ArrayList<AgentEvent>();
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
    void bashOutputChunksEmitExecutionUpdateEvents() {
        var tools = new ToolRegistry();
        tools.register(progressTool("bash"));
        var history = new MessageHistory();
        var events = new ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("call-1", "bash", "{\"command\":\"printf hi\"}"))).join();

        var updates = events.stream().filter(AgentEvent.BashExecutionUpdate.class::isInstance)
                                        .map(AgentEvent.BashExecutionUpdate.class::cast).toList();
        assertEquals(2, updates.size());
        assertEquals("call-1", updates.getFirst().toolCallId());
        assertEquals("hello ", updates.getFirst().delta());
        assertEquals("world\n", updates.get(1).delta());
        assertTrue(events.stream().anyMatch(AgentEvent.ToolResult.class::isInstance));
    }

    @Test
    void nonBashToolsDoNotEmitExecutionUpdateEvents() throws Exception {
        var tools = new ToolRegistry();
        tools.register(progressTool("not_bash"));
        var history = new MessageHistory();
        var events = new ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("call-1", "not_bash", "{}"))).get();

        assertTrue(events.stream().noneMatch(AgentEvent.BashExecutionUpdate.class::isInstance));
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
                var f = new CompletableFuture<Tool.ToolResult>();
                f.completeExceptionally(new RuntimeException("boom"));
                return f;
            }
        });
        var history = new MessageHistory();
        var events = new ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, null, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "failing", "{}"))).get();
        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertTrue(msg.content().contains("boom"));
    }

    @Test
    void extensionCanBlockToolCall() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("read"));
        var extensions = new ExtensionRegistry();
        extensions.register(blockingExtension("blocked by policy"));
        var history = new MessageHistory();
        var events = new ArrayList<AgentEvent>();
        var executor = new ToolExecutor(tools, extensions, "/cwd", history, events::add, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "read", "{}"))).get();

        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertEquals("blocked by policy", msg.content());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolResult));
    }

    @Test
    void extensionToolExecutionStartAndEndAreFired() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("read"));
        var extensions = new ExtensionRegistry();
        extensions.setAgentLoop(fakeAgentContext());
        var startCount = new AtomicInteger(0);
        var endCount = new AtomicInteger(0);
        extensions.register(new Extension() {
            @Override
            public String name() {
                return "listener";
            }

            @Override
            public String description() {
                return "listener";
            }

            @Override
            public void init(ExtensionAPI api) {
                api.on(ExtensionEvent.ToolExecutionStart.class, event -> startCount.incrementAndGet());
                api.on(ExtensionEvent.ToolExecutionEnd.class, event -> endCount.incrementAndGet());
            }
        });
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, extensions, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "read", "{}"))).get();

        assertEquals(1, startCount.get());
        assertEquals(1, endCount.get());
    }

    /**
     * Arguments that were sent but do not parse mean the reply was cut off partway through the call. Reporting the
     * schema's missing fields instead sent the model to re-read the schema and retry the same oversized call; naming
     * the real cause lets it split the work. A real one arrived as 9306 characters of unterminated JSON from a `write`
     * that hit the output ceiling.
     */
    @Test
    void truncatedArgumentsAreReportedAsTruncationNotAsAMissingField() throws Exception {
        var tools = new ToolRegistry();
        tools.register(new Tool.ToolDefinition("needsPath", "Needs path",
                                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")),
                                                                        "required", List.of("path")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "needsPath", "{\"path\": \"/tmp/x.html\", \"body\": \"<htm")))
                                        .get();

        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertTrue(msg.content().contains("cut off"), msg.content());
        assertFalse(msg.content().contains("is required"), "that diagnosis is wrong here: " + msg.content());
    }

    /** A call that parses but really is missing a field still gets the schema diagnosis, which is right for it. */
    @Test
    void invalidArgumentsProduceValidationError() throws Exception {
        var tools = new ToolRegistry();
        tools.register(new Tool.ToolDefinition("needsPath", "Needs path",
                                        Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")),
                                                                        "required", List.of("path")),
                                        (id, args, cwd) -> CompletableFuture
                                                                        .completedFuture(Tool.ToolResult.success("ok")))
                                        .toTool());
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "needsPath", "{\"unrelated\": 1}"))).get();

        assertEquals(1, history.snapshot().size());
        var msg = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(msg.isError());
        assertTrue(msg.content().contains("path"));
    }

    @Test
    void parallelExecutionContinuesAfterOneToolFails() throws Exception {
        var tools = new ToolRegistry();
        tools.register(fastTool("ok"));
        tools.register(new Tool() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public String description() {
                return "fails";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
                var f = new CompletableFuture<Tool.ToolResult>();
                f.completeExceptionally(new RuntimeException("boom"));
                return f;
            }
        });
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "ok", "{}"), new ToolCall("c2", "failing", "{}"))).get();

        assertEquals(2, history.snapshot().size());
        var okMsg = history.snapshot().stream().map(m -> (Message.ToolResult) m).filter(m -> "ok".equals(m.toolName()))
                                        .findFirst().orElseThrow();
        assertFalse(okMsg.isError());
        var failMsg = history.snapshot().stream().map(m -> (Message.ToolResult) m)
                                        .filter(m -> "failing".equals(m.toolName())).findFirst().orElseThrow();
        assertTrue(failMsg.isError());
    }

    @Test
    void sequentialExecutionContinuesAfterOneToolFails() throws Exception {
        var tools = new ToolRegistry();
        tools.register(new Tool() {
            @Override
            public String name() {
                return "first";
            }

            @Override
            public String description() {
                return "fails";
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
                var f = new CompletableFuture<Tool.ToolResult>();
                f.completeExceptionally(new RuntimeException("boom"));
                return f;
            }
        });
        tools.register(sequentialTool("second"));
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "first", "{}"), new ToolCall("c2", "second", "{}"))).get();

        assertEquals(2, history.snapshot().size());
        var first = (Message.ToolResult) history.snapshot().get(0);
        assertTrue(first.isError());
        assertEquals("second", ((Message.ToolResult) history.snapshot().get(1)).toolName());
    }

    @Test
    void anySequentialToolForcesSequentialExecution() throws Exception {
        var tools = new ToolRegistry();
        var order = new ArrayList<String>();
        tools.register(new Tool() {
            @Override
            public String name() {
                return "seq";
            }

            @Override
            public String description() {
                return "seq";
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
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
                order.add("seq");
                return CompletableFuture.completedFuture(Tool.ToolResult.success("seq"));
            }
        });
        tools.register(new Tool() {
            @Override
            public String name() {
                return "par";
            }

            @Override
            public String description() {
                return "par";
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of();
            }

            @Override
            public CompletableFuture<Tool.ToolResult> execute(String id, Tool.Args a, String cwd) {
                Map<String, Object> args = a.map();
                order.add("par");
                return CompletableFuture.completedFuture(Tool.ToolResult.success("par"));
            }
        });
        var history = new MessageHistory();
        var executor = new ToolExecutor(tools, null, "/cwd", history, e -> {}, new AtomicBoolean(false));

        executor.execute(List.of(new ToolCall("c1", "seq", "{}"), new ToolCall("c2", "par", "{}"))).get();

        assertEquals(2, history.snapshot().size());
        assertEquals(List.of("seq", "par"), order);
    }

    private static Tool progressTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Map<String, Object> schema() {
                return Map.of("type", "object");
            }

            @Override
            public CompletableFuture<ToolResult> execute(String toolCallId, Args arguments, String sessionCwd) {
                return CompletableFuture.completedFuture(ToolResult.success("done"));
            }

            @Override
            public CompletableFuture<ToolResult> execute(String toolCallId, Args arguments, String sessionCwd,
                                            Consumer<String> onProgress) {
                if (onProgress != null) {
                    onProgress.accept("hello ");
                    onProgress.accept("world\n");
                }
                return CompletableFuture.completedFuture(ToolResult.success("hello world\n"));
            }
        };
    }

    private static Extension blockingExtension(String reason) {
        return new Extension() {
            @Override
            public String name() {
                return "blocker";
            }

            @Override
            public String description() {
                return "blocks tools";
            }

            @Override
            public void init(ExtensionAPI api) {
                api.onToolCall(event -> new ExtensionResult.Block(reason));
            }
        };
    }

    private static AgentContext fakeAgentContext() {
        return new AgentContext() {
            @Override
            public LlmClient llm() {
                return new FauxLlm();
            }

            @Override
            public boolean isBusy() {
                return false;
            }

            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public void abort() {}

            @Override
            public List<Message> messages() {
                return List.of();
            }

            @Override
            public void compact(String instructions) {}

            @Override
            public void close() {}

            @Override
            public void sendUserMessage(String content) {}

            @Override
            public void sendUserMessage(String content, DeliverAs deliverAs) {}

            @Override
            public void addCustomMessage(Message.Custom message) {}
        };
    }
}
