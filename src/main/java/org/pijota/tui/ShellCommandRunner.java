package org.pijota.tui;

import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;

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

        /**
         * Renders a finished command in the shared block grammar; see {@link ToolResultLines#shellBlock}. Built by the
         * TUI, which holds the theme and the terminal width, and routed through the same helpers a tool result uses so
         * a command's output cannot look different depending on who ran it.
         */
        List<String> shellBlock(String marker, String command, String stdout, int exitCode, long elapsedMs);

        /**
         * Publishes the command currently running, so the live tail above the prompt names it, times it and says how to
         * stop it. {@code null} clears it. Without this a stuck command showed only "running shell" and a counter: you
         * could see that something was slow, not what, and nothing said Esc twice would end it.
         */
        void setRunningShellCommand(String command);
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
                host.appendPlain(host.shellBlock(marker(addToContext), command, result.stdout(), result.exitCode(),
                                                System.currentTimeMillis() - startedAt));
            } catch (Exception e) {
                // A failure to even start is reported through the same block, so the header grammar has one owner.
                host.appendPlain(host.shellBlock(marker(addToContext), command, "error: " + e.getMessage(), 1, 0));
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

    void interruptProcess() {
        if (activeProcess != null)
            ProcessRunner.killTree(activeProcess);
    }

    void interruptThread() {
        if (commandThread != null)
            commandThread.interrupt();
    }
}
