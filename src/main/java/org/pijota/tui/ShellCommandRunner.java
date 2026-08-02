package org.pijota.tui;

import org.pijota.util.Platform;
import org.pijota.util.ProcessRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ShellCommandRunner {
    interface Host extends TuiHost {
        void fireUserBash(String command, boolean noContext);

        default Map<String, String> shellEnvironment() {
            return Map.of();
        }
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
        host.appendPlain(host.promptEcho((addToContext ? "!" : "!!") + command));
        host.redrawPrompt();

        host.fireUserBash(command, !addToContext);

        long gen = host.beginBusy("running shell");
        host.redrawPrompt();
        commandThread = Thread.ofPlatform().name("pijota-shell-command").start(() -> {
            try {
                var result = ProcessRunner.command(shell, Platform.shellCommandFlag(shell), command)
                                                .redirectErrorStream(true).noTimeout().onStart(p -> activeProcess = p)
                                                .environment(host.shellEnvironment()).run();
                var out = new ArrayList<String>();
                out.add(header(command, result.exitCode()));
                String output = result.stdout().stripTrailing();
                if (!output.isBlank()) {
                    for (String line : output.split("\\R", -1))
                        out.add(line);
                }
                host.appendPlain(out);
            } catch (Exception e) {
                host.appendPlain(List.of(host.styled("toolLabel", "! " + command) + host.styled("commandMeta", " · ")
                                                + host.styled("toolError", "error: " + e.getMessage())));
            } finally {
                activeProcess = null;
                commandThread = null;
                host.endBusy(gen);
                host.redrawPrompt();
            }
        });
    }

    /**
     * The one-line header for a finished {@code !} command. The output below it stays verbatim and unrailed -- you
     * asked for a shell, you get a shell -- but the header follows the same rule as a tool line: metadata joined with
     * {@code ·}, and red on the failing token only rather than on the whole line, which used to paint the command you
     * just typed in error red as if the command itself were wrong.
     */
    String header(String command, int exitCode) {
        boolean killed = exitCode == 143 || exitCode == 137;
        String meta = killed ? "interrupted" : "exit " + exitCode;
        return host.styled("toolLabel", "! " + command) + host.styled("commandMeta", " · ")
                                        + host.styled(exitCode == 0 ? "commandMeta" : "toolError", meta);
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
