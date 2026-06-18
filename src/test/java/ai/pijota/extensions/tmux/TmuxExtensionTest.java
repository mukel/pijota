package ai.pijota.extensions.tmux;

import ai.pijota.extensions.ExtensionContext;
import ai.pijota.extensions.ExtensionEvent;
import ai.pijota.extensions.testkit.FakeExtensionAPI;
import ai.pijota.extensions.testkit.FakeExtensionRuntime;
import ai.pijota.messages.Message;
import ai.pijota.tools.Tool;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmuxExtensionTest {

    private final List<String> killed = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();

    private TmuxClient fakeClient() {
        return new TmuxClient((command, timeout) -> {
            commands.add(String.join(" ", command));
            String first = command.get(0);
            String second = command.size() > 1 ? command.get(1) : "";
            String stdout = "";
            int exit = 0;

            if ("tmux".equals(first) && "-V".equals(second)) {
                stdout = "tmux 3.4";
            } else if ("tmux".equals(first) && "new-session".equals(second)) {
                stdout = "";
            } else if ("tmux".equals(first) && "list-panes".equals(second)) {
                stdout = "%0\t1234\tpane-0";
            } else if ("tmux".equals(first) && "capture-pane".equals(second)) {
                stdout = "captured output";
            } else if ("tmux".equals(first) && "list-sessions".equals(second)) {
                stdout = "pijota-123e4567-server\t$0";
            } else if ("tmux".equals(first) && "kill-session".equals(second)) {
                killed.add(command.get(command.size() - 1));
            } else if ("tmux".equals(first) && "kill-pane".equals(second)) {
                killed.add(command.get(command.size() - 1));
            } else if ("tmux".equals(first) && "send-keys".equals(second)) {
                stdout = "";
            } else if ("tmux".equals(first) && "has-session".equals(second)) {
                exit = 0;
            }
            return new TmuxClient.Result(exit, stdout, "");
        });
    }

    private TmuxClient fakeClientMissing() {
        return new TmuxClient((command, timeout) -> {
            throw new IOException("Cannot run program \"tmux\": error=2, No such file or directory");
        });
    }

    private record Setup(FakeExtensionAPI api, TmuxExtension ext) {
    }

    private FakeExtensionAPI init(TmuxClient client) {
        return initWithExt(client).api();
    }

    private Setup initWithExt(TmuxClient client) {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of("/tmp"));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension(client);
        ext.init(api);
        api.fire(new ExtensionEvent.SessionStart(Path.of("/sessions/123e4567-1234-1234-1234-123456789012.jsonl")));
        api.fireSessionReady();
        return new Setup(api, ext);
    }

    private Tool.ToolResult runTool(FakeExtensionAPI api, String toolName, Map<String, Object> args) throws Exception {
        return api.tool(toolName).execute().execute("id", args, "/tmp").get();
    }

    // ---- registration ----

    @Test
    void registersToolsAndSlashCommands() {
        var api = init(fakeClient());
        assertNotNull(api.tool("tmux_bash"));
        assertNotNull(api.tool("tmux_send"));
        assertNotNull(api.tool("tmux_capture"));
        assertNotNull(api.tool("tmux_list"));
        assertNotNull(api.tool("tmux_kill"));
        assertNotNull(api.slashCommand("tmux-list"));
        assertNotNull(api.slashCommand("tmux-send"));
        assertNotNull(api.slashCommand("tmux-capture"));
        assertNotNull(api.slashCommand("supervise"));
        assertFalse(api.statusLineSuppliers().isEmpty());
        assertFalse(api.renderers().isEmpty());
    }

    // ---- tmux_bash ----

    @Test
    void tmuxBashStartsSession() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("Started tmux session"));
        assertTrue(result.content().contains("server"));
        assertTrue(commands.stream().anyMatch(
                                        c -> c.startsWith("tmux new-session") && c.contains("pijota-123e4567-server")));
    }

    @Test
    void tmuxBashRequiresCommand() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of());
        assertTrue(result.isError());
        assertTrue(result.content().contains("command is required"));
    }

    @Test
    void tmuxBashGeneratesNameFromCommand() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "/usr/local/bin/npm run dev"));
        assertFalse(result.isError());
        var details = result.details();
        assertNotNull(details.get("name"));
        String name = (String) details.get("name");
        assertTrue(name.startsWith("npm-"), "name was: " + name);
    }

    @Test
    void tmuxBashSanitizesUnsafeNames() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "bad name!@#"));
        assertFalse(result.isError());
        String name = (String) result.details().get("name");
        assertEquals("bad-name", name);
    }

    @Test
    void tmuxBashMakesDuplicateNamesUnique() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo 1", "name", "server"));
        var result = runTool(api, "tmux_bash", Map.of("command", "echo 2", "name", "server"));
        assertFalse(result.isError());
        assertEquals("server-2", result.details().get("name"));
    }

    @Test
    void tmuxBashUsesProvidedWorkdir() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "pwd", "workdir", "/home"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-c /home")), commands::toString);
    }

    @Test
    void tmuxBashReturnsErrorWhenTmuxMissing() throws Exception {
        var api = init(fakeClientMissing());
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("tmux is not installed"));
    }

    // ---- tmux_send ----

    @Test
    void tmuxSendResolvesFriendlyNameToPane() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "C-c"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t %0 C-c Enter")), commands::toString);
    }

    @Test
    void tmuxSendUsesRawTargetWhenNotFriendlyName() throws Exception {
        var client = fakeClient();
        var api = init(client);
        commands.clear();
        var result = runTool(api, "tmux_send", Map.of("target", "other-session", "keys", "ls"));
        assertFalse(result.isError());
        assertTrue(commands.stream().anyMatch(c -> c.contains("send-keys -t other-session ls Enter")),
                                        commands::toString);
    }

    @Test
    void tmuxSendRejectsControlCharacters() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_send", Map.of("target", "server", "keys", "hello\nworld"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("control characters"));
    }

    @Test
    void tmuxSendRequiresTargetAndKeys() throws Exception {
        var api = init(fakeClient());
        assertTrue(runTool(api, "tmux_send", Map.of("keys", "x")).isError());
        assertTrue(runTool(api, "tmux_send", Map.of("target", "server")).isError());
    }

    // ---- tmux_capture ----

    @Test
    void tmuxCaptureReturnsOutput() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_capture", Map.of("target", "server", "lines", 50));
        assertFalse(result.isError());
        assertEquals("captured output", result.content());
    }

    @Test
    void tmuxCaptureClampsLines() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        commands.clear();
        runTool(api, "tmux_capture", Map.of("target", "server", "lines", 100_000));
        assertTrue(commands.stream().anyMatch(c -> c.contains("-S -1000")), commands::toString);
    }

    @Test
    void tmuxCaptureRequiresTarget() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_capture", Map.of("lines", 50));
        assertTrue(result.isError());
    }

    // ---- tmux_list ----

    @Test
    void tmuxListShowsSessions() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("server"));
    }

    @Test
    void tmuxListPanesRequiresSession() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_list", Map.of("scope", "panes"));
        assertTrue(result.isError());
        assertTrue(result.content().contains("session is required"));
    }

    @Test
    void tmuxListPanesReturnsOutput() throws Exception {
        var api = init(fakeClient());
        var result = runTool(api, "tmux_list", Map.of("scope", "panes", "session", "pijota-123e4567-server"));
        assertFalse(result.isError());
        assertTrue(result.content().contains("%0"));
    }

    @Test
    void tmuxListReturnsErrorWhenTmuxMissing() throws Exception {
        var api = init(fakeClientMissing());
        var result = runTool(api, "tmux_list", Map.of());
        assertTrue(result.isError());
    }

    // ---- tmux_kill ----

    @Test
    void tmuxKillRemovesFriendlySession() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_kill", Map.of("target", "server"));
        assertFalse(result.isError());
        assertTrue(killed.contains("pijota-123e4567-server"));
    }

    @Test
    void tmuxKillAcceptsRawTarget() throws Exception {
        var client = fakeClient();
        var api = init(client);
        var result = runTool(api, "tmux_kill", Map.of("target", "some-other-session"));
        assertFalse(result.isError());
        assertTrue(killed.contains("some-other-session"));
    }

    @Test
    void tmuxKillToleratesAlreadyDeadSession() throws Exception {
        var client = new TmuxClient((command, timeout) -> {
            commands.add(String.join(" ", command));
            String last = command.get(command.size() - 1);
            if (command.contains("kill-session") && "pijota-123e4567-server".equals(last)) {
                return new TmuxClient.Result(1, "", "session not found");
            }
            return new TmuxClient.Result(0, "", "");
        });
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var result = runTool(api, "tmux_kill", Map.of("target", "server"));
        assertFalse(result.isError());
    }

    // ---- state persistence ----

    @Test
    void statePersistsAcrossReload() throws Exception {
        var client = fakeClient();
        var api = init(client);
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));

        var runtime2 = new FakeExtensionRuntime();
        runtime2.setCwd(Path.of("/tmp"));
        runtime2.seedMessage(new Message.Custom("tmux", api.lastSentMessage()));
        var api2 = new FakeExtensionAPI(runtime2);
        var ext2 = new TmuxExtension(client);
        ext2.init(api2);
        api2.fire(new ExtensionEvent.SessionStart(Path.of("/sessions/123e4567-1234-1234-1234-123456789012.jsonl")));
        api2.fireSessionReady();

        var result = runTool(api2, "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("server"));
    }

    @Test
    void stateLoadIgnoresMalformedLines() throws Exception {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of("/tmp"));
        runtime.seedMessage(new Message.Custom("tmux", "bad-line\n"));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension(fakeClient());
        ext.init(api);
        api.fire(new ExtensionEvent.SessionStart(Path.of("/sessions/123e4567-1234-1234-1234-123456789012.jsonl")));
        api.fireSessionReady();

        var result = runTool(api, "tmux_list", Map.of());
        assertFalse(result.isError());
        assertTrue(result.content().contains("No supervised"));
    }

    @Test
    void stateSurvivesEmptyCustomMessage() throws Exception {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of("/tmp"));
        runtime.seedMessage(new Message.Custom("tmux", ""));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension(fakeClient());
        ext.init(api);
        api.fireSessionReady();
        var result = runTool(api, "tmux_list", Map.of());
        assertFalse(result.isError());
    }

    // ---- status lines ----

    @Test
    void statusLineEmptyWhenNoSessions() {
        var api = init(fakeClient());
        var lines = api.statusLineSuppliers().getFirst().get();
        assertTrue(lines.isEmpty());
    }

    @Test
    void statusLineShowsActiveSession() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var lines = api.statusLineSuppliers().getFirst().get();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("server"));
        assertTrue(lines.getFirst().contains("1 tmux"));
    }

    @Test
    void statusLineTruncatesManySessions() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo 1", "name", "one"));
        runTool(api, "tmux_bash", Map.of("command", "echo 2", "name", "two"));
        runTool(api, "tmux_bash", Map.of("command", "echo 3", "name", "three"));
        runTool(api, "tmux_bash", Map.of("command", "echo 4", "name", "four"));
        var lines = api.statusLineSuppliers().getFirst().get();
        assertTrue(lines.getFirst().contains("+1"));
    }

    // ---- shutdown ----

    @Test
    void shutdownKillsAutoCleanupSessions() throws Exception {
        var client = fakeClient();
        var setup = initWithExt(client);
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "tmp", "cleanup_on_exit", true));
        setup.ext().onShutdown(setup.api().runtime());
        assertTrue(killed.contains("pijota-123e4567-tmp"));
    }

    @Test
    void shutdownKeepsNonAutoCleanupSessions() throws Exception {
        var client = fakeClient();
        var setup = initWithExt(client);
        runTool(setup.api(), "tmux_bash", Map.of("command", "echo hi", "name", "keeper"));
        setup.ext().onShutdown(setup.api().runtime());
        assertFalse(killed.contains("pijota-123e4567-keeper"));
    }

    // ---- message renderer ----

    @Test
    void messageRendererFormatsSessionList() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "npm run dev", "name", "server"));
        var custom = api.sentMessages().stream().filter(m -> "tmux".equals(m.customType())).findFirst().orElseThrow();
        for (var renderer : api.renderers()) {
            var rendered = renderer.apply(custom, null);
            if (rendered != null) {
                assertTrue(rendered.lines().stream().anyMatch(l -> l.contains("server")), rendered::toString);
                return;
            }
        }
    }

    @Test
    void messageRendererHandlesEmptyState() {
        var api = init(fakeClient());
        var custom = new Message.Custom("tmux", "\n");
        for (var renderer : api.renderers()) {
            var rendered = renderer.apply(custom, new ExtensionContext(null, api.runtime()));
            if (rendered != null) {
                assertEquals(List.of("no tmux sessions"), rendered.lines());
                return;
            }
        }
    }

    // ---- slash commands ----

    @Test
    void slashSuperviseRunsCommand() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("npm run dev");
        assertTrue(api.lastSentMessage().contains("Started tmux session")
                                        || api.lastSentMessage().contains("npm run dev"));
    }

    @Test
    void slashSuperviseShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("   ");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashSendShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("tmux-send").handler().accept("");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashSendShowsUsageWhenMissingKeys() {
        var api = init(fakeClient());
        api.slashCommand("tmux-send").handler().accept("server");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashCaptureShowsUsageWhenEmpty() {
        var api = init(fakeClient());
        api.slashCommand("tmux-capture").handler().accept("  ");
        assertTrue(api.lastSentMessage().contains("Usage"));
    }

    @Test
    void slashCommandsUseCustomNoteType() {
        var api = init(fakeClient());
        api.slashCommand("supervise").handler().accept("echo hi");
        var note = api.sentMessages().getLast();
        assertTrue(note.customType().startsWith("tmux"));
    }

    // ---- session prefix ----

    @Test
    void sessionPrefixDerivedFromSessionFile() throws Exception {
        var api = init(fakeClient());
        runTool(api, "tmux_bash", Map.of("command", "echo hi", "name", "server"));
        assertTrue(commands.stream().anyMatch(c -> c.contains("pijota-123e4567-server")), commands::toString);
    }

    @Test
    void sessionPrefixFallsBackToRandomWhenPathIsNull() throws Exception {
        var runtime = new FakeExtensionRuntime();
        runtime.setCwd(Path.of("/tmp"));
        var api = new FakeExtensionAPI(runtime);
        var ext = new TmuxExtension(fakeClient());
        ext.init(api);
        api.fire(new ExtensionEvent.SessionStart(null));
        api.fireSessionReady();
        var result = runTool(api, "tmux_bash", Map.of("command", "echo hi"));
        assertFalse(result.isError());
        String tmuxName = (String) result.details().get("tmux_name");
        assertNotNull(tmuxName);
        assertTrue(tmuxName.startsWith("pijota-"));
    }

    // ---- missing tmux across all tools ----

    @Test
    void allToolsReportMissingTmux() throws Exception {
        var api = init(fakeClientMissing());
        assertTrue(runTool(api, "tmux_bash", Map.of("command", "x")).isError());
        assertTrue(runTool(api, "tmux_send", Map.of("target", "s", "keys", "x")).isError());
        assertTrue(runTool(api, "tmux_capture", Map.of("target", "s")).isError());
        assertTrue(runTool(api, "tmux_list", Map.of()).isError());
        assertTrue(runTool(api, "tmux_kill", Map.of("target", "s")).isError());
    }
}
