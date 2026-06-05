package ai.pijota.agent;

import ai.pijota.extensions.ExtensionRegistry;
import ai.pijota.messages.Message;
import ai.pijota.messages.ToolCall;
import ai.pijota.tools.Tool;
import ai.pijota.tools.ToolRegistry;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry tools;
    private final ExtensionRegistry extensions;
    private final String cwd;
    private final MessageHistory history;
    private final Consumer<AgentEvent> onEvent;
    private final AtomicBoolean aborted;

    ToolExecutor(ToolRegistry tools, ExtensionRegistry extensions, String cwd,
                 MessageHistory history,
                 Consumer<AgentEvent> onEvent,
                 AtomicBoolean aborted) {
        this.tools = tools;
        this.extensions = extensions;
        this.cwd = cwd;
        this.history = history;
        this.onEvent = onEvent;
        this.aborted = aborted;
    }

    CompletableFuture<Void> execute(List<ToolCall> requests) {
        if (requests.isEmpty()) return CompletableFuture.completedFuture(null);
        if (requests.size() == 1) return executeOne(requests.get(0));
        boolean anySequential = requests.stream().anyMatch(r -> {
            var tool = tools.get(r.name());
            return tool.isPresent() && tool.get().executionMode() == Tool.ExecutionMode.SEQUENTIAL;
        });
        if (anySequential) return executeSequential(requests);
        return executeParallel(requests);
    }

    private CompletableFuture<Void> executeSequential(List<ToolCall> requests) {
        var future = new CompletableFuture<Void>();
        executeSequentially(requests, 0, future);
        return future;
    }

    private void executeSequentially(List<ToolCall> requests,
                                     int idx, CompletableFuture<Void> future) {
        if (idx >= requests.size() || aborted.get()) { future.complete(null); return; }
        executeOne(requests.get(idx))
            .thenRun(() -> executeSequentially(requests, idx + 1, future))
            .exceptionally(ex -> {
                log.warn("Tool execution error (continuing): {}", ex.getMessage());
                executeSequentially(requests, idx + 1, future);
                return null;
            });
    }

    private CompletableFuture<Void> executeParallel(List<ToolCall> requests) {
        var futures = requests.stream()
            .map(r -> executeOne(r).exceptionally(ex -> {
                log.warn("Tool {} failed (continuing): {}", r.name(), ex.getMessage());
                return null;
            }))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> executeOne(ToolCall request) {
        onEvent.accept(new AgentEvent.ToolStart(request.name(), request.id(), request::arguments));
        try {
            if (extensions != null) {
                var blockReason = extensions.beforeToolCall(request.id(), request.name(), request.arguments());
                if (blockReason.isPresent()) {
                    var blocked = new Message.ToolResult(request.id(), request.name(), blockReason.get(), true);
                    history.add(blocked);
                    onEvent.accept(new AgentEvent.ToolResult(blocked));
                    return CompletableFuture.completedFuture(null);
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> args = Util.parseJsonArgs(request.arguments());
            if (extensions != null)
                extensions.fireToolExecutionStart(request.id(), request.name(), request.arguments());
            return tools.execute(request.id(), request.name(), args, request.arguments(), cwd)
                .thenAccept(toolResult -> {
                    if (extensions != null) {
                        extensions.fireToolExecutionEnd(request.id(), request.name(), toolResult, toolResult.isError());
                        extensions.afterToolCall(request.id(), request.name(), toolResult);
                    }
                    var msg = new Message.ToolResult(
                        request.id(), request.name(), toolResult.content(), toolResult.isError(), toolResult.details());
                    history.add(msg);
                    onEvent.accept(new AgentEvent.ToolResult(msg));
                })
                .exceptionally(ex -> {
                    var errorResult = Tool.ToolResult.error("Tool error: " + ErrorUtil.message(ex));
                    if (extensions != null)
                        extensions.fireToolExecutionEnd(request.id(), request.name(), errorResult, true);
                    var msg = new Message.ToolResult(request.id(), request.name(),
                        "Tool error: " + ErrorUtil.message(ex), true);
                    history.add(msg);
                    onEvent.accept(new AgentEvent.ToolResult(msg));
                    return null;
                });
        } catch (Exception e) {
            var errorResult = Tool.ToolResult.error("Tool error: " + ErrorUtil.message(e));
            if (extensions != null)
                extensions.fireToolExecutionEnd(request.id(), request.name(), errorResult, true);
            var msg = new Message.ToolResult(request.id(), request.name(),
                "Tool error: " + ErrorUtil.message(e), true);
            history.add(msg);
            onEvent.accept(new AgentEvent.ToolResult(msg));
            return CompletableFuture.completedFuture(null);
        }
    }

}
