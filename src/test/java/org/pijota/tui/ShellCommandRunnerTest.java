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

    /**
     * The output used to print bare at column zero: nothing separated a command's result from the prose above it, and
     * nothing said where one command's output ended and the next began. It reads as a block now, the same grammar a
     * bash tool call uses -- header, railed body, footer with what it cost.
     */
    @Test
    void aFinishedCommandReadsAsABlock() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host, "/bin/sh");

        var out = runner.block("git push", true, "To github.com:mukel/pijota.git\n   e3e3945..e68f9e4  main\n", 0, 0);

        assertEquals("", out.get(0), "a block claims one blank line of air");
        assertEquals("! git push", out.get(1), "the header names the command, and ! says you ran it, not the model");
        assertEquals("│ To github.com:mukel/pijota.git", out.get(2), "output is railed, so it cannot be read as prose");
        assertEquals("│    e3e3945..e68f9e4  main", out.get(3));
        assertEquals("└ 2 lines · exit 0", out.get(4), "and the footer closes it with what it cost");
    }

    /** {@code !!} keeps the command out of the conversation; the mark is the only place that difference is visible. */
    @Test
    void theMarkSaysWhetherTheCommandJoinedTheConversation() {
        var runner = new ShellCommandRunner(new FakeHost(), "/bin/sh");
        assertEquals("! ls · exit 0", runner.block("ls", true, "", 0, 0).get(1));
        assertEquals("!! ls · exit 0", runner.block("ls", false, "", 0, 0).get(1));
    }

    /**
     * Red on the failing token only, and the exit code stays up in the header where it is read rather than in a footer
     * below the error text. The whole line used to go red -- the command you had just typed included.
     */
    @Test
    void aFailureKeepsItsExitCodeInTheHeaderAndIsRedOnlyThere() {
        var slots = new ArrayList<String>();
        var host = new FakeHost() {
            @Override
            public String styled(String slot, String text) {
                slots.add(slot + ":" + text);
                return text;
            }
        };
        var runner = new ShellCommandRunner(host, "/bin/sh");

        var out = runner.block("ls /nope", true, "ls: cannot access '/nope'\n", 2, 0);

        assertEquals("! ls /nope · exit 2", out.get(1));
        assertEquals("│ ls: cannot access '/nope'", out.get(2));
        assertEquals("└", out.get(3), "the block still closes, with nothing left to say");
        assertTrue(slots.contains("toolLabel:! ls /nope"), "the command itself is never red: " + slots);
        assertTrue(slots.contains("toolError:exit 2"), "only the exit code is: " + slots);
    }

    @Test
    void aCommandWithNoOutputIsASingleLine() {
        var runner = new ShellCommandRunner(new FakeHost(), "/bin/sh");
        var out = runner.block("touch x", true, "", 0, 0);
        assertEquals(2, out.size(), "air and one line, with no rail to open and close: " + out);
        assertEquals("! touch x · exit 0", out.get(1));
    }

    /** A killed command says so rather than reporting the signal's exit code as a failure. */
    @Test
    void anInterruptedCommandSaysInterrupted() {
        var runner = new ShellCommandRunner(new FakeHost(), "/bin/sh");
        assertEquals("! sleep 9 · interrupted", runner.block("sleep 9", true, "", 143, 0).get(1));
        assertEquals("! sleep 9 · interrupted", runner.block("sleep 9", true, "", 137, 0).get(1));
    }

    /** A command slow enough to wonder about reports how long it has been going. */
    @Test
    void aSlowCommandReportsItsDuration() {
        var runner = new ShellCommandRunner(new FakeHost(), "/bin/sh");
        assertTrue(runner.block("build", true, "done\n", 0, 12_000).getLast().contains("12s"));
        assertFalse(runner.block("build", true, "done\n", 0, 200).getLast().contains("0s"),
                                        "a command that returns at once is not timed");
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
        public List<String> railed(List<String> body) { // the real host rails through ToolResultLines
            return body.stream().filter(l -> !l.isBlank()).map(l -> "│ " + l).toList();
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
