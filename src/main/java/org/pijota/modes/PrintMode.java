package org.pijota.modes;

import org.pijota.agent.AgentEvent;
import org.pijota.agent.AgentLoop;
import org.pijota.agent.PijotaSession;
import org.pijota.cli.Args;
import org.pijota.messages.Message;
import org.pijota.messages.Usage;
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

    /**
     * @param session
     *            the run's session, not its {@link AgentLoop}. Print mode used to reach past it and prompt the loop
     *            directly, which skipped the one place a user turn is written to disk: a {@code -p} session on disk
     *            held the answer with no question, so {@code --continue}, {@code --fork} and {@code /load} resumed it
     *            with the model talking to itself. It also skipped the input filter and the agent-start hooks every
     *            other frontend fires.
     */
    public static int run(String prompt, PijotaSession session, Args.OutputMode outputMode) {
        try {
            var result = streamAndWait(prompt, session, outputMode);
            var agentLoop = session.agentLoop();
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

    private static List<Message> streamAndWait(String prompt, PijotaSession session, Args.OutputMode outputMode)
                                    throws ExecutionException, InterruptedException {
        AgentLoop agentLoop = session.agentLoop();
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
            } else if (event instanceof AgentEvent.Retry(int attempt, int max, long delayMs, String why)) {
                // stderr, so a caller piping stdout still gets a clean answer: the wait is the only thing being
                // reported here, and a silent minute reads as a hang.
                if (outputMode != Args.OutputMode.JSON)
                    System.err.println(Util.retryLine(attempt, max, delayMs, why));
            } else if (event instanceof AgentEvent.Error(var t)) {
                if (!done.isDone())
                    done.completeExceptionally(t);
            }
        };
        agentLoop.addEventListener(listener);

        try {
            var future = session.prompt(prompt); // the session persists the user turn; the loop alone does not
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
        payload.put("usage", new Usage(in, out, cacheRead, cacheWrite, reasoning));
        new JsonEventStream(System.out).write(payload);
    }
}
