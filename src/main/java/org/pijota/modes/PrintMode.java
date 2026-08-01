package org.pijota.modes;

import org.pijota.agent.AgentEvent;
import org.pijota.agent.AgentLoop;
import org.pijota.cli.Args;
import org.pijota.messages.Message;
import org.pijota.util.CliErrorReporter;
import org.pijota.util.ErrorUtil;
import org.pijota.util.Util;

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

        Consumer<AgentEvent> listener = event -> {
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
     * The machine-readable result of a print run: which model answered, what it said, and what it cost. The model was
     * previously hardcoded to "unknown", and usage was absent entirely, so a script driving pijota could neither
     * attribute nor budget a run. Serialized through Jackson rather than hand-built strings, so content escaping is the
     * mapper's problem.
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
        payload.put("provider", agentLoop.llm().provider());
        payload.put("model", agentLoop.llm().model());
        payload.put("messages", messages);
        payload.put("usage", Map.of("inputTokens", in, "outputTokens", out, "cacheReadTokens", cacheRead,
                                        "cacheWriteTokens", cacheWrite, "reasoningTokens", reasoning));
        try {
            System.out.println(Util.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
        } catch (Exception e) {
            CliErrorReporter.warn("could not serialize JSON result: " + ErrorUtil.message(e));
        }
    }
}
