package org.pijota.tui;

import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;
import org.pijota.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the user's own {@code !}/{@code !!} shell commands and renders them in the same block grammar a bash tool call
 * uses: a header naming the command, the output behind a {@code │} rail, and a footer carrying what it cost. They used
 * to print their output bare at column zero, which left nothing to separate a command's result from the assistant's
 * prose above it, and nothing to say where one command's output ended and the next began.
 */
final class ShellCommandRunner {
    interface Host extends TuiHost {
        void fireUserBash(String command, boolean noContext);

        default Map<String, String> shellEnvironment() {
            return Map.of();
        }

        /** Rails and wraps a block body to the terminal's width; see {@link ToolResultLines#railedBody}. */
        default List<String> railed(List<String> body) {
            return body;
        }

        /**
         * Publishes the command currently running, so the live tail above the prompt names it, times it and says how to
         * stop it. {@code null} clears it. Without this a stuck command showed only "running shell" and a counter: you
         * could see that something was slow, not what, and nothing said Esc twice would end it.
         */
        default void setRunningShellCommand(String command) {}
    }

    private final Host host;
    private final String shell; // resolved shell (config / $SHELL / OS default), portable across Unix & Windows
    private volatile Process activeProcess;
    private volatile Thread commandThread;

    ShellCommandRunner(Host host, String shell) {
        this.host = host;
        this.shell = shell;
    }

    void run(String command, boolean addToContext) {
        if (command.isBlank())
            return;
        host.fireUserBash(command, !addToContext);

        // No phase: the tail names the command, and "running shell" beside it was the vaguer half of the same thing
        // said twice, with two timers between them.
        long gen = host.beginBusy(null);
        host.setRunningShellCommand(command);
        long startedAt = System.currentTimeMillis();
        host.redrawPrompt();
        commandThread = Thread.ofPlatform().name("pijota-shell-command").start(() -> {
            try {
                var result = ProcessRunner.command(shell, Platform.shellCommandFlag(shell), command)
                                                .redirectErrorStream(true).noTimeout().onStart(p -> activeProcess = p)
                                                .environment(host.shellEnvironment()).run();
                host.appendPlain(block(command, addToContext, result.stdout(), result.exitCode(),
                                                System.currentTimeMillis() - startedAt));
            } catch (Exception e) {
                host.appendPlain(List.of("", host.styled("toolLabel", marker(addToContext) + " " + command)
                                                + host.styled("commandMeta", " · ")
                                                + host.styled("toolError", "error: " + e.getMessage())));
            } finally {
                activeProcess = null;
                commandThread = null;
                host.setRunningShellCommand(null);
                host.endBusy(gen);
                host.redrawPrompt();
            }
        });
    }

    /** {@code !} keeps the command in the conversation, {@code !!} does not; the mark says which happened. */
    private static String marker(boolean addToContext) {
        return addToContext ? "!" : "!!";
    }

    /**
     * The finished command as a block, in the same three shapes a tool result takes: no output is a single line
     * carrying its own metadata; output succeeds into a railed body with a {@code └} footer; a failure keeps the exit
     * code up in the header, red on that token alone, and closes bare.
     */
    List<String> block(String command, boolean addToContext, String stdout, int exitCode, long elapsedMs) {
        boolean killed = exitCode == 143 || exitCode == 137;
        String status = killed ? "interrupted" : "exit " + exitCode;
        String statusSlot = exitCode == 0 ? "commandMeta" : "toolError";
        String dot = host.styled("commandMeta", " · ");
        String head = host.styled("toolLabel", marker(addToContext) + " " + command);

        var body = host.railed(stdout == null ? List.of() : List.of(stdout.stripTrailing().split("\\R", -1)));
        var out = new ArrayList<String>();
        out.add(""); // a block claims one blank line of air, like every other one
        if (body.isEmpty()) {
            out.add(head + dot + host.styled(statusSlot, status));
            return out;
        }
        var foot = new ArrayList<String>();
        if (exitCode == 0) { // a clean exit is footer metadata; a failure stays up in the header where it is read
            out.add(head);
            foot.add(host.styled("commandMeta", Util.count(body.size(), "line", "lines")));
            foot.add(host.styled("commandMeta", status));
        } else {
            out.add(head + dot + host.styled(statusSlot, status));
        }
        if (elapsedMs >= 1000)
            foot.add(host.styled("commandMeta", Util.formatDuration(elapsedMs)));
        out.addAll(body);
        out.add(host.styled("commandMeta", "└") + (foot.isEmpty() ? "" : " " + String.join(dot, foot)));
        return out;
    }

    void interruptProcess() {
        if (activeProcess != null)
            ProcessRunner.killTree(activeProcess);
    }

    void interruptThread() {
        if (commandThread != null)
            commandThread.interrupt();
    }
}
