package org.pijota.tui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellCommandRunnerTest {

    @Test
    void runWithBlankCommandDoesNothing() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        runner.run("", true);

        assertTrue(host.echoedCommands.isEmpty());
        assertEquals(0, host.busyCount);
    }

    @Test
    void runWithWhitespaceCommandDoesNothing() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        runner.run("   ", true);

        assertTrue(host.echoedCommands.isEmpty());
    }

    @Test
    void runFiresUserBashWithCorrectNoContextFlag() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        runner.run("ls", false);
        assertEquals("ls", host.firedCommand);
        assertTrue(host.firedNoContext);

        host.firedCommand = null;
        runner.run("ls", true);
        assertEquals("ls", host.firedCommand);
        assertFalse(host.firedNoContext);
    }

    /**
     * The command is not echoed as its own line any more: it used to be stated once as "▸ !git push" and then again one
     * row below as the result header. The block header states it, and while it runs the live tail above the prompt
     * names it -- feedback that keeps counting rather than a line that just sits there.
     */
    @Test
    void aRunningCommandIsNamedInTheLiveTailAndNotEchoedTwice() throws Exception {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        runner.run("echo hi", true);

        assertTrue(host.echoedCommands.isEmpty(), "no separate echo line: " + host.echoedCommands);
        assertEquals("echo hi", host.runningCommand, "the tail is told what is running before the process starts");
        for (int i = 0; i < 200 && host.runningCommand != null; i++)
            Thread.sleep(10);
        assertNull(host.runningCommand, "and told when it is over, so the tail does not outlive the command");
        assertTrue(String.join("\n", host.allLines).contains("! echo hi"), host.allLines.toString());
    }

    @Test
    void runEntersBusyState() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        runner.run("ls", true);

        assertEquals(1, host.busyCount);
    }

    private static class FakeHost implements ShellCommandRunner.Host {
        final List<String> echoedCommands = new ArrayList<>();
        final List<String> allLines = new ArrayList<>();
        int busyCount;
        volatile String runningCommand;
        String firedCommand;
        boolean firedNoContext;

        @Override
        public List<String> promptEcho(String text) {
            echoedCommands.add(text);
            return List.of(text);
        }

        @Override
        public void appendPlain(List<String> lines) {
            allLines.addAll(lines);
        }

        @Override
        public String styled(String slot, String text) {
            return text;
        }

        @Override
        public List<String> shellBlock(String marker, String command, String stdout, int exitCode, long elapsedMs) {
            // The real host renders through ToolResultLines.shellBlock; ShellCommandRunnerTest covers the runner,
            // ToolResultLinesTest covers the grammar.
            return List.of(marker + " " + command);
        }

        @Override
        public long beginBusy(String phase) {
            busyCount++;
            return 0;
        }

        @Override
        public void setRunningShellCommand(String command) {
            runningCommand = command;
        }

        @Override
        public void endBusy(long generation) {}

        @Override
        public void redrawPrompt() {}

        @Override
        public void fireUserBash(String command, boolean noContext) {
            firedCommand = command;
            firedNoContext = noContext;
        }
    }
}
