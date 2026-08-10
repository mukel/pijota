package org.pijota.tui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What is running right now: the model's tool calls, and the user's own {@code !} command.
 *
 * <p>
 * State only. Which of them the live tail line describes is a rendering question and stays with the interface, but
 * "what is in flight, and since when" is not -- it was two fields and a hand-rolled max scan inside a three-thousand
 * line class, reachable only by driving a terminal. The rule that matters is that the newest wins: a tool call fired
 * while a {@code !} command is still running must not leave the tail describing the older of the two.
 *
 * <p>
 * Concurrent by construction. Tool calls are entered and cleared from the agent's turn thread while the repaint reads
 * them ~8 times a second from the UI thread.
 */
final class InFlight {

    /** A tool call the model has started and whose result has not arrived. {@code args} is re-read when it lands. */
    record Tool(String name, String detail, Supplier<String> args, long startedAtMs) {
    }

    /** The user's own {@code !} command, which runs beside the turn rather than inside it. */
    record Shell(String command, long startedAtMs) {
    }

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile Shell shell;

    void toolStarted(String callId, String name, String detail, Supplier<String> args, long startedAtMs) {
        tools.put(callId, new Tool(name, detail, args, startedAtMs));
    }

    /** Removes the call and hands back what was known about it, or null if it was never entered. */
    Tool toolFinished(String callId) {
        return callId == null ? null : tools.remove(callId);
    }

    void shellStarted(String command, long startedAtMs) {
        shell = command == null ? null : new Shell(command, startedAtMs);
    }

    Shell shell() {
        return shell;
    }

    /** The most recently started tool call, or null when none is running. */
    Tool newestTool() {
        Tool newest = null;
        for (var tool : tools.values())
            if (newest == null || tool.startedAtMs() > newest.startedAtMs())
                newest = tool;
        return newest;
    }

    boolean noTools() {
        return tools.isEmpty();
    }

    /** Drops every call and returns them oldest-first, which is the order their headers are committed in. */
    List<Map.Entry<String, Tool>> takeAll() {
        var taken = tools.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getValue().startedAtMs()))
                                        .toList();
        tools.clear();
        return taken;
    }

    /** Forgets any leftovers from a turn that was interrupted or aborted before its results arrived. */
    void clearTools() {
        tools.clear();
    }
}
