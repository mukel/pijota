package ai.pijota.tui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShellCommandRunnerTest {

    @Test
    void runWithBlankCommandDoesNothing() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("", true);

        assertTrue(host.echoedCommands.isEmpty());
        assertEquals(0, host.busyCount);
    }

    @Test
    void runWithWhitespaceCommandDoesNothing() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("   ", true);

        assertTrue(host.echoedCommands.isEmpty());
    }

    @Test
    void runFiresUserBashWithCorrectNoContextFlag() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("ls", false);
        assertEquals("ls", host.firedCommand);
        assertTrue(host.firedNoContext);

        host.firedCommand = null;
        runner.run("ls", true);
        assertEquals("ls", host.firedCommand);
        assertFalse(host.firedNoContext);
    }

    @Test
    void runEchoesCommandWithCorrectPrefix() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("ls", true);
        assertEquals("!ls", host.echoedCommands.get(0));

        runner.run("ls", false);
        assertEquals("!!ls", host.echoedCommands.get(1));
    }

    @Test
    void runEntersBusyState() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("ls", true);

        assertEquals(1, host.busyCount);
    }

    @Test
    void runIncludesCommandInLinesWhenEchoed() {
        var host = new FakeHost();
        var runner = new ShellCommandRunner(host);

        runner.run("echo hello", true);

        assertEquals("!echo hello", host.echoedCommands.get(0));
    }

    private static final class FakeHost implements ShellCommandRunner.Host {
        final List<String> echoedCommands = new ArrayList<>();
        final List<String> allLines = new ArrayList<>();
        int busyCount;
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
        public long beginBusy(String phase) {
            busyCount++;
            return 0;
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
