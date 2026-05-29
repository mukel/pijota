package ai.pijota.tui;

import ai.pijota.util.ProcessRunner;

import java.util.ArrayList;
import java.util.List;

final class ShellCommandRunner {
    interface Host {
        List<String> promptEcho(String text);
        void appendPlain(List<String> lines);
        String styled(String slot, String text);
        long beginBusy(String phase);
        void endBusy(long generation);
        void redrawPrompt();
        void fireUserBash(String command, boolean noContext);
    }

    private final Host host;
    private volatile Process activeProcess;
    private volatile Thread commandThread;

    ShellCommandRunner(Host host) {
        this.host = host;
    }

    void run(String command, boolean addToContext) {
        if (command.isBlank()) return;
        host.appendPlain(host.promptEcho((addToContext ? "!" : "!!") + command));
        host.redrawPrompt();

        host.fireUserBash(command, !addToContext);

        long gen = host.beginBusy("running shell");
        host.redrawPrompt();
        commandThread = Thread.ofPlatform().name("pijota-shell-command")
            .start(() -> {
                try {
                    var result = ProcessRunner.command("/bin/sh", "-lc", command)
                        .redirectErrorStream(true)
                        .noTimeout()
                        .onStart(p -> activeProcess = p)
                        .run();
                    var out = new ArrayList<String>();
                    String slot = result.exitCode() == 0 ? "toolLabel" : "toolError";
                    String suffix = result.exitCode() == 143 || result.exitCode() == 137
                        ? "  interrupted" : "  exit " + result.exitCode();
                    out.add(host.styled(slot, "! " + command + suffix));
                    String output = result.stdout().stripTrailing();
                    if (!output.isBlank()) {
                        for (String line : output.split("\\R", -1)) out.add(line);
                    }
                    host.appendPlain(out);
                } catch (Exception e) {
                    host.appendPlain(List.of(host.styled("toolError", "! " + command + "  error: " + e.getMessage())));
                } finally {
                    activeProcess = null;
                    commandThread = null;
                    host.endBusy(gen);
                    host.redrawPrompt();
                }
            });
    }

    void interruptProcess() {
        if (activeProcess != null) ProcessRunner.killTree(activeProcess);
    }

    void interruptThread() {
        if (commandThread != null) commandThread.interrupt();
    }
}
