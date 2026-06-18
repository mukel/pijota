package ai.pijota.extensions.tmux;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmuxClientTest {

    private final List<List<String>> commands = new ArrayList<>();

    private TmuxClient fakeClient(int exitCode, String stdout, String stderr) {
        return new TmuxClient((command, timeout) -> {
            commands.add(new ArrayList<>(command));
            return new TmuxClient.Result(exitCode, stdout, stderr);
        });
    }

    @Test
    void hasTmuxReturnsTrueWhenVersionSucceeds() {
        var client = fakeClient(0, "tmux 3.4", "");
        assertTrue(client.hasTmux());
        assertEquals(List.of("tmux", "-V"), commands.getLast());
    }

    @Test
    void hasTmuxReturnsFalseWhenCommandFails() {
        var client = fakeClient(1, "", "not found");
        assertFalse(client.hasTmux());
    }

    @Test
    void hasTmuxReturnsFalseOnIOException() {
        var client = new TmuxClient((command, timeout) -> {
            throw new IOException("boom");
        });
        assertFalse(client.hasTmux());
    }

    @Test
    void newSessionRunsCorrectCommandWithWorkdir() {
        var client = fakeClient(0, "", "");
        var result = client.newSession("pijota-abc-server", Path.of("/tmp"), "npm run dev");
        assertTrue(result.success());
        var cmd = commands.getLast();
        assertEquals("tmux", cmd.get(0));
        assertEquals("new-session", cmd.get(1));
        assertEquals("-d", cmd.get(2));
        assertEquals("-s", cmd.get(3));
        assertEquals("pijota-abc-server", cmd.get(4));
        assertEquals("-c", cmd.get(5));
        assertEquals("/tmp", cmd.get(6));
        assertEquals("npm run dev", cmd.get(7));
    }

    @Test
    void newSessionOmitsWorkdirWhenNull() {
        var client = fakeClient(0, "", "");
        var result = client.newSession("pijota-abc-server", null, "echo hi");
        assertTrue(result.success());
        var cmd = commands.getLast();
        assertEquals(List.of("tmux", "new-session", "-d", "-s", "pijota-abc-server", "echo hi"), cmd);
    }

    @Test
    void newSessionOmitsWorkdirWhenMissing() {
        var client = fakeClient(0, "", "");
        var result = client.newSession("pijota-abc-server", Path.of("/does/not/exist"), "echo hi");
        assertTrue(result.success());
        var cmd = commands.getLast();
        assertEquals(List.of("tmux", "new-session", "-d", "-s", "pijota-abc-server", "echo hi"), cmd);
    }

    @Test
    void newSessionPropagatesStderrOnFailure() {
        var client = fakeClient(1, "", "duplicate session: pijota-abc-server");
        var result = client.newSession("pijota-abc-server", null, "cmd");
        assertFalse(result.success());
        assertTrue(result.stderr().contains("duplicate session"));
    }

    @Test
    void sendKeysAppendsEnterByDefault() {
        var client = fakeClient(0, "", "");
        var result = client.sendKeys("pijota-abc-server", "C-c", true);
        assertTrue(result.success());
        assertEquals(List.of("tmux", "send-keys", "-t", "pijota-abc-server", "C-c", "Enter"), commands.getLast());
    }

    @Test
    void sendKeysWithoutEnter() {
        var client = fakeClient(0, "", "");
        client.sendKeys("pijota-abc-server", "hello", false);
        assertEquals(List.of("tmux", "send-keys", "-t", "pijota-abc-server", "hello"), commands.getLast());
    }

    @Test
    void sendKeysWithSpecialCharacters() {
        var client = fakeClient(0, "", "");
        client.sendKeys("%0", "git status", true);
        assertEquals(List.of("tmux", "send-keys", "-t", "%0", "git status", "Enter"), commands.getLast());
    }

    @Test
    void capturePaneUsesNegativeStartLine() {
        var client = fakeClient(0, "line1\nline2", "");
        var result = client.capturePane("%42", 100);
        assertTrue(result.success());
        assertEquals("line1\nline2", result.stdout());
        assertEquals(List.of("tmux", "capture-pane", "-t", "%42", "-p", "-S", "-100"), commands.getLast());
    }

    @Test
    void capturePaneClampsZeroLinesToOne() {
        var client = fakeClient(0, "x", "");
        client.capturePane("%0", 0);
        assertEquals(List.of("tmux", "capture-pane", "-t", "%0", "-p", "-S", "-1"), commands.getLast());
    }

    @Test
    void listSessionsParsesFormatString() {
        var client = fakeClient(0, "pijota-abc\t$0\npijota-def\t$1", "");
        var result = client.listSessions();
        assertTrue(result.success());
        assertEquals("pijota-abc\t$0\npijota-def\t$1", result.stdout());
        assertEquals(List.of("tmux", "list-sessions", "-F", "#{session_name}\t#{session_id}"), commands.getLast());
    }

    @Test
    void listPanesUsesSessionTarget() {
        var client = fakeClient(0, "%0\t1234\tpane-0\n%1\t5678\tpane-1", "");
        var result = client.listPanes("pijota-abc-server");
        assertTrue(result.success());
        assertEquals(List.of("tmux", "list-panes", "-t", "pijota-abc-server", "-F",
                                        "#{pane_id}\t#{pane_pid}\t#{pane_title}"), commands.getLast());
    }

    @Test
    void sessionExistsReturnsTrueOnlyOnSuccess() {
        var client = fakeClient(0, "", "");
        assertTrue(client.sessionExists("pijota-abc-server"));
        assertEquals(List.of("tmux", "has-session", "-t", "pijota-abc-server"), commands.getLast());

        var missing = fakeClient(1, "", "session not found");
        assertFalse(missing.sessionExists("pijota-abc-server"));
    }

    @Test
    void killSessionRunsCorrectCommand() {
        var client = fakeClient(0, "", "");
        var result = client.killSession("pijota-abc-server");
        assertTrue(result.success());
        assertEquals(List.of("tmux", "kill-session", "-t", "pijota-abc-server"), commands.getLast());
    }

    @Test
    void killPaneRunsCorrectCommand() {
        var client = fakeClient(0, "", "");
        var result = client.killPane("%0");
        assertTrue(result.success());
        assertEquals(List.of("tmux", "kill-pane", "-t", "%0"), commands.getLast());
    }

    @Test
    void missingBinaryReturnsActionableError() {
        var client = new TmuxClient((command, timeout) -> {
            throw new IOException("Cannot run program \"tmux\": error=2, No such file or directory");
        });
        var result = client.newSession("x", null, "cmd");
        assertEquals(-1, result.exitCode());
        assertNotNull(result.stderr());
        assertTrue(result.stderr().contains("tmux not found"));
    }

    @Test
    void genericIOExceptionReturnsErrorResult() {
        var client = new TmuxClient((command, timeout) -> {
            throw new IOException("generic failure");
        });
        var result = client.capturePane("%0", 10);
        assertEquals(-1, result.exitCode());
        assertEquals("generic failure", result.stderr());
    }
}
