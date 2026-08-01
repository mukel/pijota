package org.pijota.modes;

import org.pijota.agent.AgentEvent;
import org.pijota.agent.AgentLoop;
import org.pijota.cli.Args;
import org.pijota.messages.Message;
import org.pijota.util.CliErrorReporter;
import org.pijota.util.ErrorUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class PrintMode {

    public static int run(String prompt, AgentLoop agentLoop, Args.OutputMode outputMode) {
        try {
            var result = streamAndWait(prompt, agentLoop, outputMode);
            if (outputMode == Args.OutputMode.JSON) {
                printJson(result, agentLoop);
            }
            return 0;
        } catch (ExecutionException e) {
            Throwable cause = ErrorUtil.unwrap(e);
            if (cause instanceof InterruptedException)
                return 1; // interrupted (e.g. Ctrl-C), exit quietly
            return CliErrorReporter.fatal("Print mode failed", cause);
        } catch (Exception e) {
            return CliErrorReporter.fatal("Print mode failed", e);
        }
    }

    private static List<Message> streamAndWait(String prompt, AgentLoop agentLoop, Args.OutputMode outputMode)
                                    throws ExecutionException, InterruptedException {
        var lastContent = new AtomicReference<>("");
        var done = new CompletableFuture<List<Message>>();
        var textStarted = new AtomicBoolean(false);

        var events = outputMode == Args.OutputMode.JSON ? new JsonEventStream(System.out) : null;

        Consumer<AgentEvent> listener = event -> {
            if (events != null)
                events.emit(event); // NDJSON: one line per event, live, so a wrapper can follow the run
            if (event instanceof AgentEvent.AssistantMessage(var msg, var usage)) {
                if (outputMode == Args.OutputMode.JSON)
                    return;
                String content = msg.content() == null ? "" : msg.content();
                String prev = lastContent.get();
                if (content.startsWith(prev)) {
                    String delta = content.substring(prev.length());
                    if (!delta.isEmpty()) {
                        textStarted.set(true);
                        System.out.print(delta);
                        try {
                            System.out.flush();
                        } catch (Exception ignored) {}
                    }
                }
                lastContent.set(content);
                if (usage != null && !done.isDone()) {
                    if (textStarted.get())
                        System.out.println();
                    try {
                        System.out.flush();
                    } catch (Exception ignored) {}
                }
            } else if (event instanceof AgentEvent.Error(var t)) {
                if (!done.isDone())
                    done.completeExceptionally(t);
            }
        };
        agentLoop.addEventListener(listener);

        try {
            var future = agentLoop.prompt(prompt);
            future.whenComplete((result, error) -> {
                if (error != null)
                    done.completeExceptionally(error);
                else
                    done.complete(result);
            });
            return done.get();
        } finally {
            agentLoop.removeEventListener(listener);
        }
    }

    /**
     * The final line of the JSON stream: which model answered, what it said, and what it cost. It carries
     * {@code "type": "result"} like every other line, so a consumer can read the stream uniformly and stop at the
     * result; {@code tail -1} alone is enough for a caller that only wants the outcome.
     */
    private static void printJson(List<Message> result, AgentLoop agentLoop) {
        var messages = new ArrayList<Map<String, Object>>();
        long in = 0, out = 0, cacheRead = 0, cacheWrite = 0, reasoning = 0;
        for (var msg : result) {
            if (!(msg instanceof Message.Assistant a))
                continue;
            if (a.content() != null && !a.content().isBlank())
                messages.add(Map.of("role", "assistant", "content", a.content()));
            if (a.usage() != null) { // every request of the turn contributes, not just the final message
                in += a.usage().inputTokens();
                out += a.usage().outputTokens();
                cacheRead += a.usage().cacheReadTokens();
                cacheWrite += a.usage().cacheWriteTokens();
                reasoning += a.usage().reasoningTokens();
            }
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("type", "result");
        payload.put("provider", agentLoop.llm().provider());
        payload.put("model", agentLoop.llm().model());
        payload.put("messages", messages);
        payload.put("usage", Map.of("inputTokens", in, "outputTokens", out, "cacheReadTokens", cacheRead,
                                        "cacheWriteTokens", cacheWrite, "reasoningTokens", reasoning));
        new JsonEventStream(System.out).write(payload);
    }
}
