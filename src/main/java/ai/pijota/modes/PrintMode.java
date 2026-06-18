package ai.pijota.modes;

import ai.pijota.agent.AgentEvent;
import ai.pijota.agent.AgentLoop;
import ai.pijota.cli.Args;
import ai.pijota.messages.Message;
import ai.pijota.util.CliErrorReporter;
import ai.pijota.util.ErrorUtil;
import ai.pijota.util.Util;

import java.util.List;
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
                printJson(result);
            }
            return 0;
        } catch (ExecutionException e) {
            Throwable cause = ErrorUtil.unwrap(e);
            if (cause instanceof InterruptedException)
                return 1; // interrupted (e.g. Ctrl-C) — exit quietly
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

    private static void printJson(List<Message> result) {
        System.out.println("{");
        System.out.println("  \"model\": \"unknown\",");
        System.out.println("  \"messages\": [");
        boolean first = true;
        for (var msg : result) {
            if (msg instanceof Message.Assistant a && a.content() != null && !a.content().isBlank()) {
                if (!first)
                    System.out.println(",");
                first = false;
                System.out.println("    {");
                System.out.println("      \"role\": \"assistant\",");
                System.out.println("      \"content\": \"" + Util.escapeJson(a.content()) + "\"");
                System.out.print("    }");
            }
        }
        System.out.println("\n  ]");
        System.out.println("}");
    }
}
